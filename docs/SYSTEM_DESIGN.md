# KanbanCord system design

How KanbanCord works today: its parts, the decisions behind them, and the known gaps (section 14).
It describes the current state, not plans; ideas for later are in the README's backlog.

## 1. Product and principles

KanbanCord is a kanban board that lives inside Discord servers: boards, columns and tasks, with
assignees, labels, priorities, due dates and comments, used mostly from Discord and optionally from
the website.

These principles shaped the design:

- **Discord first.** The bot is a complete client: everything except editing permission rules can
  be done from Discord. The website is for the big picture (whole boards, drag and drop) and for the
  finer settings.
- **Discord is the source of truth for who is who.** Servers, members, roles and channels are synced
  from Discord; KanbanCord never changes them.
- **Permissions follow Discord.** Access comes from a member's Discord roles and permissions from the
  start, with optional rules on top.
- **Simple until asked otherwise.** New servers start in simple mode (tasks with a title and a
  description); every other feature is switched on when wanted.
- **Show every control, check on use.** Bot views show the same buttons to everyone; whether the
  person may do it is checked when they click.
- **Nobody is told about their own changes**, and nobody is pinged twice for the same change.
- **One place decides.** The API is the only part that writes data or decides permissions; the bot
  and the website are its clients.

## 2. Components

```
                     Discord (gateway and REST)
                        │                ▲
     events, commands,  │                │  posts, threads, DMs,
     interactions       ▼                │  board posts
 ┌───────────────────────────┐      ┌────┴───────────────────────────┐      ┌──────────────────────┐
 │ bot (discord.js, sharded) │─────▶│ kanbancord-api (Spring Boot)   │◀─────│ website (React SPA)  │
 │ commands, views, sync,    │      │ all data, permissions,         │      │ boards, settings,    │
 │ delivery workers          │      │ notification routing           │      │ guides               │
 └───────────────────────────┘      └────────────────┬───────────────┘      └──────────────────────┘
   bot token (+ acting user)                         │                         access token + WebSocket
                                                     ▼
                                                PostgreSQL
```

- **API** (`kanbancord-api`): Java 21, Spring Boot 3.5, PostgreSQL with Flyway migrations, Spring
  Security, STOMP over WebSockets.
- **Bot** (`kanbancord-bot`): Node.js 22, discord.js 14 with automatic sharding.
- **Website** (`kanbancord-frontend`): React 19, TypeScript, Vite, TanStack Query; public pages are
  prerendered to HTML; served by nginx.

## 3. Data model

The main tables, by area:

| Area | Tables |
|---|---|
| Discord mirror | `servers` (with `bot_present`, `open_permissions`), `users`, `server_members`, `roles`, `member_roles`, `discord_channels` (with whether the bot may post and make threads) |
| Work | `boards`, `columns`, `tasks`, `task_assignments` (people), `task_role_assignments` (roles), `labels`, `task_labels`, `board_priorities`, `task_comments`, `task_comment_edits`, `task_followers` |
| Access | `kanban_permissions` (the catalog), `permissions` (rules) |
| Features | `server_features`, `board_disabled_features` |
| Notifications | `notification_feeds`, `feed_board_settings`, `server_notification_settings` (audit channel), `user_notification_settings`, `notification_queue`, `task_due_reminders` |
| Discord output | `board_posts`, `board_thread_settings`, `task_threads` |
| History | `audit_log` |
| Accounts | `user_sessions`, `discord_credentials`, `media_uploads` |

Discord ids (servers, users, roles, channels) are stored as `BIGINT` and sent as strings in JSON,
since they do not fit in a JavaScript number. Due dates are UTC times without a zone.

## 4. Identity and authentication

Every request resolves to an actor:

| Caller | Authenticates with | Acts as |
|---|---|---|
| Website | `Authorization: Bearer <access token>` naming a sign-in session | the signed-in Discord user |
| Bot, syncing and delivering | `X-Internal-Bot-Token` (only its hash is kept) | the system, on `/api/internal/*` |
| Bot, running a command | bot token plus `X-Acting-User-Id` and `X-Acting-Guild-Id` | that user, under the same permission checks as the website, only within that server |

- **Identity always comes from authentication**, never from the request: controllers get the user
  from `@CurrentUser`, and request fields such as `createdBy` are ignored. Changes made through the
  bot are recorded in the audit log as coming from Discord.
- **401 for unauthenticated, 403 for not allowed.**
- **Internal endpoints** (`/api/internal/*`) are only reachable by the bot: nginx blocks them from
  outside.

### Sessions (website)

1. Signing in with Discord (OAuth2) creates a row in `user_sessions`. The access token is a 15-minute
   JWT carrying the session id; tokens of revoked or expired sessions are rejected (checked through a
   30-second cache that revocations clear at once).
2. The refresh token is only ever an `httpOnly`, `Secure`, `SameSite=Strict` cookie scoped to
   `/api/auth`; only its hash is stored. Each refresh rotates it; the token just replaced is accepted
   for 60 seconds (tabs refreshing together), and presenting it after that revokes the session.
   Sessions end 30 days after their last refresh.
3. Signing out revokes the session; `GET /api/me/sessions` lists them and they can be revoked one by
   one or all at once. Revoking closes that session's WebSockets.
4. The Discord tokens stay on the server, encrypted with AES-256-GCM, and are used for the user's
   guild list (cached 5 minutes). They are deleted and revoked at Discord when the last session ends.
5. The website keeps the access token in memory only; refreshes are coordinated across tabs.
6. The JWT secret must be at least 32 bytes; without one, signing in is disabled.

## 5. Authorization

### 5.1 Model

- **Catalog**: `KanbanPermissionCatalog`, mirrored in `kanban_permissions`. Each key (`VIEW_BOARD`,
  `CREATE_TASK`, `ASSIGN_TASK_OTHERS`, `MANAGE_SERVER_PERMISSIONS`, ...) has a category, the scopes
  it may be set at, and a rank (ADMIN > SERVER_MANAGE > BOARD_MANAGE > STANDARD > READONLY).
- **Rules**: rows in `permissions` with a scope (server, or a board's overrides), a subject (a person,
  a role, or a Discord permission bit), a key, and ALLOW or DENY.
- **Defaults** (`DefaultPermissionRules`): what each Discord permission allows out of the box, each
  step adding to the one before: View Channels → Send Messages → Manage Messages → Manage Channels →
  Manage Server; View Audit Log separately; Administrator (and the server owner) everything.

### 5.2 Which rules apply

- **Custom permissions off** (the default): the code defaults apply, and no board has rules of its
  own. Stored rules are kept, unused, for when custom permissions are switched back on.
- **Custom permissions on**: the server's stored rules and each board's overrides apply. A new server
  gets the defaults as its stored rules to start from; syncing a server never resets edited rules.
- **Open permissions** (exclusive with custom permissions): everyone who can view channels and send
  messages may do the everyday work (boards, columns, tasks, labels, priorities, assigning) whatever
  the rules say. Managing the server, board permissions, deleting or archiving boards and the audit
  log still follow the rules.

### 5.3 Resolution

Implemented in `PermissionResolver` (pure, no I/O) and pinned by its tests.

1. Administrators and the server owner are allowed everything. Administrator can be given by a rule
   but never denied, so a server cannot lock out the people who run it.
2. With open permissions on, the everyday keys are allowed for everyone who can talk.
3. Otherwise rules are read in six layers: the server's rules for Discord permissions, roles, then
   the person; then the board's, in the same order. A layer counts only if it has a rule for this
   person and key, and **the last such layer decides**.
4. Within a layer, a DENY beats any ALLOW (two roles disagreeing means no).
5. No matching rule means no.

`PermissionEvaluationService` loads everything for one person in a fixed handful of queries (a
`PermissionSnapshot`) and resolves any number of keys from it; checking many boards loads all their
rules in one query.

### 5.4 Changing rules and checking access

- **Who may change rules** (`PermissionEscalationGuardService`): server rules need
  `MANAGE_SERVER_PERMISSIONS`; a board's rules need `EDIT_BOARD_PERMISSIONS` on it. Below ADMIN,
  people can only touch keys and subjects ranked below themselves, and nobody can remove their own
  right to manage server permissions. Changes are checked by applying them to an in-memory snapshot.
- **Check access** (`/permissions/check`): what a member, a member with other roles, or any set of
  roles may do, server-wide or on a board, with the rule that decided each key and the rules it
  outweighed. Uses the same resolver as enforcement. Checking others needs
  `MANAGE_SERVER_PERMISSIONS`, or `EDIT_BOARD_PERMISSIONS` on that board.

### 5.5 Enforcement

- Every endpoint checks a permission, not just membership: reads need `VIEW_SERVER`, `VIEW_BOARD` or
  `VIEW_TASK`; each write needs its own key, evaluated at board scope for anything inside a board.
- Lists only show boards the person can view; the bot's autocomplete likewise.
- Updating a task needs `EDIT_TASK` only if its content changes and `MOVE_TASK` only if it moves.
- Comments are edited only by their authors; `DELETE_TASK_COMMENT` lets moderators remove others'.
- `/permissions/mine` gives the caller every key, server-wide and per visible board, so clients show
  the right controls without asking key by key.

## 6. Features and simple mode

- **Features** (`server_features`): labels, priorities, assignees, comments, due dates and custom
  permissions, each switched on per server. With none on, the server is in simple mode.
- **Per board** (`board_disabled_features`): a board can switch off features the server has on.
- **When a feature is off**, the API rejects its writes, its data is left out of responses but kept,
  and saving a task where a field is hidden keeps the stored value rather than clearing it.
- Features, open permissions and everything else except the rules themselves can be changed from
  Discord (`/kanbancord features`) as well as on the website.

## 7. Discord sync

The bot keeps the API's copy of each server current, through `/api/internal/sync/*`:

- **On joining a server, on start-up (once the API answers), and every 12 hours**: a full bootstrap
  of the server, its owner, roles and members (members and roles Discord no longer has are removed).
  The regular re-sync catches changes whose events were missed while the API was down.
- **As things change**: member joins, updates and leaves; role changes; server changes.
- **Channels**: the server's text and announcement channels, with whether the bot may post and make
  threads in each, re-sent shortly after channel or permission changes settle.

Sync changes are announced over realtime (so open pages update) but not audited.

## 8. Changes, audit log and realtime

- Every change publishes a **domain event** with the actor, what changed (before and after) and
  whether it came from Discord or the website.
- After the transaction commits, listeners turn it into an **audit log** entry, a **realtime**
  message, a **notification** queue entry, and marks on **board posts** that need redrawing.
- **Realtime** is STOMP over WebSockets. Clients subscribe to `/topic/servers/{id}` and
  `/topic/servers/{id}/boards/{id}`; changes that concern one person only (their profile,
  notification settings, sessions) go to `/user/queue/me`. Subscribing needs `VIEW_SERVER` or
  `VIEW_BOARD`; board events on the server topic are withheld from people who cannot view the board.
  When someone loses access, their subscription ends and they are told (`SUBSCRIPTION_REVOKED`).
- **Opening a WebSocket**: a browser cannot send an `Authorization` header with a WebSocket, so the
  website first asks for a one-time ticket (`POST /api/realtime/tickets`, valid 30 seconds, tied to
  the sign-in session) and opens the socket with it. The ticket is deleted when used.

## 9. Notifications and delivery

Changes reach people through update feeds, the audit log channel, and direct messages. The API
decides who hears what; the bot sends it.

1. **Queue** (`NotificationQueue`): each change that can be announced joins the open group for what
   it concerns (usually one task). A group is due 30 seconds after its first change, so quick edits
   go out as one message.
2. **Routing** (`NotificationRouter`): when the bot claims due groups, each becomes a **plan**:
   - **Feeds** (`notification_feeds`): each feed posts in one channel, for every board or chosen
     boards, with its own choice of events and of which events mention the people involved (for an
     assignment, the person assigned; otherwise the task's assignees, and its roles if the feed
     mentions roles). Boards can change a feed's events and mentions for themselves
     (`feed_board_settings`). Feeds sharing a channel post once. **Interactive** feeds show the whole
     task with buttons under each update.
   - **The audit log channel**: every change, pinging nobody.
   - **Direct messages**: to the task's creator, assignees and followers (and, if they choose,
     people who commented), for the events each person chose, and only if they can still see the
     board. A person's mode is "always", "only if not already mentioned" (the default) or "never",
     with a per-server setting on top.
   - **The task's thread**, for boards with threads (section 10).
3. **Delivery** (bot, shard 0): posts to channels first, then sends direct messages, skipping
   "only if not mentioned" people whom a post just mentioned in a channel they can see. It reports
   each plan delivered or failed; unreported plans are claimed again after 5 minutes, and a group
   that keeps failing (5 attempts) or is over an hour old is dropped.
4. **Due date reminders** (`DueReminderScheduler`, every minute): once when a task is due within a
   day and once when it becomes overdue, for tasks not done (not in the last column), sent like
   other notifications.

Nobody is told or pinged about their own changes. Every direct message has a button to stop messages
from its server.

## 10. Board posts and task threads

- **Board posts** (`/board post`): a whole board as one Discord message that redraws itself. Changes
  mark a board's posts out of date; the bot (shard 0) claims posts once they have settled for a
  moment, redraws them, and reports which were done, gone (message deleted) or need retrying. At
  most 10 posts per board and 100 per server. Before posting, the bot warns if people in the channel
  could not see the board otherwise.
- **Task threads** (`/board threads`, or board settings): a board can give each task its own thread
  in one of its feed channels, public (started from the task's post) or private (the task's creator
  and assignees). Each plan for a task on such a board says which thread, if any, and where updates
  go (the thread, the channel, or both, with mentions made once). The bot makes the thread when
  something first happens to the task, or at once from a task's **Discuss in thread** button, and
  reports it back (`task_threads`). Threads follow the task's title, take in new assignees when
  private, and are archived with the task. Threads stop working, without being deleted, if the feed
  channel stops covering the board.

## 11. The bot

- **Sharding**: discord.js `ShardingManager` with automatic shard count. Shard 0 registers the
  commands and runs the background workers: notification delivery, board post redraws, bot-list
  server counts (every 30 minutes) and the status page heartbeat (every minute).
- **Commands**: slash commands (`/board`, `/task`, `/column`, `/label`, `/priority`, `/comment`,
  `/kanbancord`, `/notifications`, `/guide`, `/help`, `/report`) and the **Create task** message
  command.
- **Interactions**: buttons, menus and forms carry versioned custom ids
  (`kc1:<feature>:<action>:<args>`), routed to handlers by feature; ids from an older version are
  refused politely. Every handler acts as the person who clicked, through the API.
- **Views** are Discord Components V2 containers: board, column, task, comments and settings panels
  link to each other. A view made for one person, clicked by someone else, answers that person
  privately instead of changing it for everyone.
- **Caching**: short-lived caches of board lists and snapshots (a few seconds) keep autocomplete and
  forms within Discord's three-second limit.
- **Guidance**: `/guide` walks through setup with ticks for what the server has done
  (`/api/servers/{id}/guide`), and managers of a brand-new server get a one-time simple-mode tip.

## 12. The website

- **Structure**: features in `src/features` (board, board settings, server settings, notifications,
  access check, audit, preferences, session, ...), typed API clients in `src/services`, public pages
  and guides in `src/site`.
- **Data**: TanStack Query caches; realtime events trigger a debounced refetch of the affected board
  or server data.
- **Public pages** (landing, guides, FAQ, legal) are prerendered to HTML at build time for speed and
  search engines, and carry structured data identifying the app.
- **Demo mode** (`npm run demo`) runs the whole site against made-up data in the browser.

## 13. Deployment and operations

- Pushes to `main` in each repository build a Docker image and publish it to the GitHub Container
  Registry. The server pulls new images itself; nothing logs in to it from GitHub.
- The API and website run behind nginx and Cloudflare; nginx blocks `/api/internal/*` from outside.
- Rate limits: sign-in per IP, writes per user, and uploads per user on top.
- Images and videos are uploaded through the API to Imgur; only the links are kept.
- A public status page monitors the website and API, and the bot reports a heartbeat.

## 14. Known gaps

| Gap | Effect | When it matters |
|---|---|---|
| Realtime tickets are kept in the API's memory | A ticket only works on the instance that issued it | Running more than one API instance: store them in the database or Redis |
| Realtime events trigger refetches rather than updating the cache directly | More requests than needed after each change | Large boards or many viewers |
| Permission snapshots are not cached between requests | Each request loads one (a handful of queries) | Higher traffic: cache per request, then briefly per server and user |
| The server's own rules are listed to every member | Members can see who may do what server-wide (board rules stay hidden from those who cannot see the board) | If servers want their rules private |
| No HTTP compression; website routes are not lazy-loaded | Larger downloads on first load | Slow connections |
| Task threads are created from Discord events only | A thread is not created for a task until something happens to it, unless someone presses Discuss in thread | By design; no change planned |
