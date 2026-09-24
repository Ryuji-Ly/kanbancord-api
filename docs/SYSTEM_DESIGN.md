# KanbanCord system design

Status: living document. Everything here is either implemented or the agreed direction. Known gaps
are listed in section 9.

## 1. Product goal

KanbanCord is a Kanban system that lives inside Discord servers.

- **The bot is a complete client for day-to-day work.** Viewing boards and tasks, creating,
  editing, moving and deleting tasks, and assigning people are all possible without opening the
  website.
- **The website is the power-user surface.** It handles board configuration, permissions, themes,
  accessibility, audit history and bulk work.
- **Discord is the identity and membership source of truth.** Servers, members and roles are
  synced by the bot; KanbanCord never edits them.
- **Notifications reach people where they are:**
  - DMs on assignment, according to each user's preferences.
  - A per-server update channel, via webhook, for events such as task created, moved or assigned,
    and board or column created.

## 2. Components

```
            Discord gateway / REST
                 │          ▲
   events, cmds  ▼          │ DMs, webhooks
 ┌──────────────────┐   ┌──┴─────────────────────────────┐   ┌───────────────┐
 │ bot (discord.js) │──▶│ kanbancord-api (Spring Boot)   │◀──│ frontend (SPA)│
 │ sync + commands  │   │ sole owner of writes + authz   │   │ React + Vite  │
 └──────────────────┘   └──────────────┬─────────────────┘   └───────────────┘
   X-Internal-Bot-Token                │ JPA / Flyway           JWT + STOMP
   (+ X-Acting-User-Id)                ▼
                                   PostgreSQL
```

The API is the only component that writes to the database or decides permissions. The bot and the
frontend are clients of it.

## 3. Identity and authentication

### 3.1 Actors

Every request resolves to an **actor**:

| Source | How it authenticates | Actor |
|---|---|---|
| Web | `Authorization: Bearer <JWT>` naming a sign-in session (see 3.2) | the Discord user in the JWT |
| Bot, sync | `X-Internal-Bot-Token` (only its SHA-256 is stored in config) | system; no user permissions apply |
| Bot, acting for a user *(planned)* | bot token + `X-Acting-User-Id` | that user, with `source = BOT`, under the same authorization as web |

Rules:

- **Identity always comes from authentication, never from the request.** Controllers receive the
  user through `@CurrentUser Long userId`, which is resolved from the verified JWT. The legacy
  `?userId=` query parameter is ignored. Request fields such as `createdBy` and `assignedBy` are
  ignored too, and the actor is recorded instead.
- **Unauthenticated requests get 401. Authenticated but unauthorized requests get 403.**
- **The JWT secret must be at least 32 bytes.** Startup fails with a shorter one. An empty secret
  disables login.

### 3.2 Sessions

1. **Sessions.** Signing in with Discord creates a row in `user_sessions` (user, created, last used,
   expiry, revoked, user agent). The access token is a 15-minute JWT carrying the session id (`sid`);
   the filter rejects tokens whose session is revoked or expired, checked through a 30-second cache
   that revocations on this instance evict at once. Tokens without `sid` are rejected.
2. **Refresh cookie.** The refresh token is sent only as an `httpOnly`, `Secure`, `SameSite=Strict`
   cookie scoped to `/api/auth`; only its SHA-256 is stored. `POST /api/auth/refresh` rotates it on
   every use. The token just replaced is accepted for 60 seconds (tabs refreshing at once, or a
   response lost to a reload), and the current token is handed out again; each replacement is an HMAC
   of the previous token, so this needs no stored secret. Presenting a replaced token after that
   revokes the session. Cookie endpoints require an allowed `Origin`. Sessions end 30 days after
   their last refresh.
3. **Signing out.** `POST /api/auth/logout` revokes the cookie's session. `GET /api/me/sessions`
   lists active sessions; `DELETE /api/me/sessions/{id}` and `DELETE /api/me/sessions` (all others)
   revoke them. Revocation closes the session's WebSockets (close code 4401).
4. **Discord token server-side.** The Discord access and refresh tokens are stored in
   `discord_credentials`, encrypted with AES-256-GCM (`KANBANCORD_TOKEN_ENCRYPTION_KEY`, or a key
   derived from the JWT secret), bound to their user. `GET /api/me/guilds` uses them, refreshing when
   needed, cached 5 minutes. When Discord rejects them the endpoint returns 409 and the user signs in
   again. They are deleted and revoked at Discord when the user's last session ends.
5. **Browser.** The web app keeps the access token in memory only and nothing in `localStorage`.
   Refreshes are serialised across tabs with a Web Lock, and sign-in and sign-out are broadcast to
   other tabs.
6. **Bot acting-user filter** *(planned)* as described in 3.1. It is only accepted from the bot
   token, and every such action is audited with `source = BOT`.

## 4. Authorization

### 4.1 Model

- **Catalog.** `KanbanPermissionCatalog` in code, mirrored in `kanban_permissions` by migrations. It
  is read-only through the API. Each key has a category, allowed scopes and a rank (ADMIN >
  SERVER_MANAGE > BOARD_MANAGE > STANDARD > READONLY).
- **Rules.** Rows in `permissions` (server rules, plus board overrides; see 4.4) with:
  - `scope`: SERVER or BOARD
  - `subject`: USER, ROLE or DISCORD_PERMISSION bit
  - `key`
  - `state`: ALLOW or DENY
  - `priority`
  - `is_immutable`: system-owned rules only; the API never creates or keeps one
- **Defaults.** When a server is first bootstrapped, Discord permission bits are mapped to Kanban
  keys (see `PermissionBootstrapService`). ADMINISTRATOR maps to an immutable ADMIN rule, and the
  server owner is treated as ADMINISTRATOR.
- **Escalation guard.** Nobody can change rules at or above their own rank, or lock themselves
  out of managing permissions.

### 4.2 Enforcement

- **Every endpoint checks a permission, not just membership:**
  - Server-level reads require `VIEW_SERVER`.
  - Board structure (board, columns, labels) requires `VIEW_BOARD`.
  - Task content (tasks, comments, assignments, task labels) requires `VIEW_TASK`.
  - Every write requires its specific key.
- **Anything inside a board is evaluated at board scope** (`requireBoardPermission`). That check
  also verifies the board belongs to the server in the path.
- **Board lists are filtered to boards the user can view.**
- **Realtime subscriptions** require `VIEW_BOARD` for a board topic and `VIEW_SERVER` for a server
  topic.
- **Behaviour-specific rules:**
  - Updating a task needs `EDIT_TASK` only if its content changes, and `MOVE_TASK` only if its
    column or position changes. A request that changes nothing does not write.
  - Assigning yourself needs `ASSIGN_TASK_SELF`. Assigning anyone else needs `ASSIGN_TASK_OTHERS`,
    and the assignee must be a server member.
  - Authors edit and delete their own comments with `CREATE_TASK_COMMENT`.
    `EDIT_TASK_COMMENT` and `DELETE_TASK_COMMENT` are moderation permissions for other people's
    comments.
  - Evaluating another member's permissions requires `MANAGE_SERVER_PERMISSIONS`.

### 4.3 Evaluation engine

- **`PermissionEvaluationService` loads a `PermissionSnapshot` with a fixed handful of queries:**
  membership, roles (with Discord bits), owner, server rules and board rules.
- **`PermissionResolver` then evaluates any number of keys in memory**, with no I/O.
- **Batch operations reuse the snapshot:** `resolveAll`, `calculateEffectiveRank`, and
  `filterAllowedBoards` (which loads all board rules in one query).
- **Cost:** one permission check used to cost roughly 60–130 queries, depending on the user's roles
  and Discord bits. It now costs 5 plus the board-scope check.
- **Next step:** a per-request cache of the snapshot, then a short-TTL cache keyed by
  (server, user). Invalidate it on rule changes and on bot role or member syncs.

### 4.4 Permission resolution

Implemented in `PermissionResolver` and pinned by `PermissionEvaluationServiceTest`.

1. **ADMIN.** A user who resolves to ADMIN at server scope is allowed every key.
2. **Layers.** Rules are applied as layers in this order, and each later layer overrides the earlier
   ones:
   - server scope: DISCORD_PERMISSION rules, then ROLE rules, then USER rules
   - board scope: DISCORD_PERMISSION rules, then ROLE rules, then USER rules
3. **Layers without a match are skipped.** A layer only takes effect if it has at least one rule
   matching the user and the key. So board scope always overrides server scope wherever the board
   has a rule, and otherwise the board inherits the server result.
4. **Within a layer, DENY beats ALLOW.** For example, with two roles where one allows and one
   denies, the result is denied. A rule state other than ALLOW counts as DENY.
5. **No matching layer means denied.**

`priority` no longer affects resolution. The column is kept for display order.

**Board inheritance.** New boards store no rules and inherit everything from the server. Board rules
are overrides only. The dashboard shows inherited rules alongside overrides, and saving stores only
entries that differ from what they inherit. Migration V9 removed the rules that older boards had
copied from the server and that still matched it. Copies whose server rule had changed since are
kept as overrides, because they can no longer be told apart from deliberate edits.

**Who may change rules** (`PermissionEscalationGuardService`):
- Server-scope rules require MANAGE_SERVER_PERMISSIONS or ADMIN.
- Board-scope rules require EDIT_BOARD_PERMISSIONS on that board, or server management.
- Unless the actor is ADMIN, they can only touch keys ranked below their own effective rank. They
  can also only touch USER or ROLE subjects ranked below theirs. So an EDIT_BOARD_PERMISSIONS holder
  (BOARD_MANAGE rank) can make a board private, or open it to a role, but cannot hand out
  board-management keys or create boards.
- A change that would remove the actor's own ability to manage server permissions is rejected.
- Changes are checked by applying them to an in-memory snapshot, using the same rules as
  enforcement.

### 4.5 Simple mode

**Decided:**

- **Where it is set.** Simple mode is a server-level setting.
- **Defaults.** New servers start in SIMPLE. Existing servers are migrated to ADVANCED, so their
  behaviour does not change. Admins can switch either way.
- **Content permissions.** In SIMPLE mode the authorizer ignores rules for content. Any member with
  `VIEW_CHANNEL` can view, create, edit, move, delete and archive tasks and columns, comment,
  assign, and create or edit boards.
- **All boards are public.** Board overrides do not apply in SIMPLE mode, so boards cannot be made
  private.
- **Keys that stay restricted** to Discord Manage Server, Administrator or the owner:
  `ARCHIVE_BOARD`, `DELETE_BOARD`, `MANAGE_SERVER_PERMISSIONS`, `VIEW_AUDIT_LOG`, `ADMIN`.
- **`EDIT_BOARD_PERMISSIONS`** can be granted to people in ADVANCED mode. It has no effect in
  SIMPLE mode, since there are no board overrides there.
- **Switching modes.** Going back to ADVANCED restores the stored rules untouched.
- **Configurable features.** Separately from the permission mode, `server_settings` holds
  `labels_enabled`, `priorities_enabled`, `due_dates_enabled`, and room for more. The SIMPLE preset
  turns them off, and each can be changed individually. The API rejects writes to disabled features
  and omits their fields; clients hide the UI.

**Schema:** `servers.permission_mode` (`SIMPLE` or `ADVANCED`, default `SIMPLE`, with existing rows
migrated to `ADVANCED`) and a `server_settings` table.

## 5. Domain additions (planned migrations)

| Feature | Schema |
|---|---|
| Priority dropdown (staff request) | `priority_levels(id, server_id, board_id NULL, name, color, rank, is_default)`. Server defaults are High / Medium / Low / Ignorable; a board can override the list. `tasks.priority_id` is an FK. Existing free-text priorities are matched by name or become board-level entries. |
| Assign to a role (staff request) | `task_assignments.user_id` becomes nullable, plus a new `role_id`. A `CHECK` requires exactly one of them, with unique indexes per task. Covered by `ASSIGN_TASK_OTHERS`. |
| Labels UI | The backend already exists. It needs the UI plus the `labels_enabled` flag. |
| User preferences | Typed `/api/me/preferences`: theme, accessibility palette (deuteranopia, protanopia, tritanopia, high contrast), DM notification settings. Replaces the untyped JSON field written through a server-scoped endpoint. Other users can no longer read it. |
| Image uploads | `POST /api/media/images` proxies to Imgur with the client id server-side. It is rate limited and returns a URL that goes into markdown. Rendering is markdown-only with no raw HTML (XSS fix). |
| Discord integrations | `server_integrations(server_id, update_channel_webhook (encrypted), event_filter)`. |
| Delivery outbox | `outbound_messages(id, kind, target, payload, attempts, next_attempt_at, sent_at)`. |

## 6. Events and side effects

**Target:**

1. Application services publish **domain events**, such as `TaskMoved` or `TaskAssigned`, carrying
   the actor and a before/after diff.
2. After commit, listeners fan them out:
   - realtime STOMP broadcast (already delivered after commit)
   - an audit log row, which makes "audit every mutation" automatic instead of per-endpoint
   - in-app notifications
   - outbox entries for DMs and update-channel webhooks, which a dispatcher sends through Discord
     REST, honouring rate limits and retrying

**Realtime:**

- Events carry the changed entity. Clients apply them to their cache instead of refetching the
  board.
- A per-board revision number lets a client detect gaps and refetch only then.
- Server-topic events must not reveal boards a subscriber cannot view.

## 7. Target code structure

**Backend: package by feature, with thin controllers.**

```
com.kanbancord
  common/     errors, web config, security (actor, JWT, bot auth), pagination
  identity/   auth, sessions, users, preferences
  guild/      servers, roles, members, internal sync API
  access/     catalog, rules, snapshot/resolver, simple mode, escalation guard
  board/      boards, columns, tasks, assignments, labels, priorities, comments
  activity/   domain events → audit, notifications, realtime, outbox dispatcher
  media/      image uploads
```

Each feature is split into four layers:

- `api`: controllers, DTOs and mappers. Controllers never touch repositories.
- `application`: transactional use cases. Authorization happens here.
- `domain`: entities and rules.
- `infrastructure`: repositories and external clients.

The current `AccessValidator` / `ResourceValidator` / `BusinessValidationService` trio becomes an
`Authorizer` plus per-feature loaders.

**Frontend:**

```
src/
  app/        router, providers (TanStack Query, auth), route-level code splitting
  api/        one typed client (auth header, error mapping) + per-feature endpoints
  features/   auth, dashboard, boards (BoardView, Column, TaskCard, TaskDrawer, CommentThread), permissions, preferences
  realtime/   one STOMP connection; events patch the query cache
  ui/         shared components, theme tokens, accessibility palettes
```

**Bot:**

- Keep the structure: sync events, plus command modules.
- Commands call the API as the acting user (see 3.1).
- Bot responses use the same permission decisions as the web.

## 8. Performance plan

**Done:**

- Permission checks reduced from about 100 queries to about 5, and batched.
- Board visibility filtering uses one query for all board rules.
- Task updates that change nothing skip the write and the broadcast.
- The frontend bundle dropped from 700 KB to 529 KB by removing `rehype-raw`.

**Next:**

1. `GET /boards/{id}/snapshot`: board, columns, tasks, assignments, labels and the caller's
   capabilities in one request. This removes the `/me` → 5-request waterfall.
2. `POST /tasks/{id}/move` and `POST /columns/{id}/move` with fractional positions. One write
   replaces renumbering a whole column.
3. Apply realtime event payloads instead of refetching the board.
4. Include capabilities in board list responses, removing N permission requests per dashboard.
5. Cache the guild list server-side (see 3.2).
6. Route-level lazy loading. Split `BoardPage`.
7. Enable `server.compression`, and use fetch joins or batch fetching for comment and assignment
   authors.

## 9. Security posture

**Fixed in the security pass:**

- Global permission catalog could be mutated.
- Members could self-assign roles.
- Audit logs could be forged.
- Comments could be soft-deleted with no permission check.
- Stored XSS in markdown.
- Identity was taken from `?userId=`.
- Board-level rules were never enforced.
- `VIEW_*` was never enforced on reads or realtime.
- Assignment and label permissions were never enforced.
- `createdBy` / `assignedBy` could be spoofed.
- Clients could create immutable rules.
- Any member could evaluate other members' permissions.
- Other users' preferences were readable.
- No minimum JWT secret length.
- Actuator health details were public.
- A board-level DENY could not restrict a server-level ALLOW. Fixed by the resolution model in 4.4.
- Unauthenticated requests returned 403 instead of 401.
- JWTs could not be revoked, and the app and Discord tokens were kept in `localStorage` (3.2).
- Realtime subscriptions survived losing access until the client reconnected. Permission,
  role and membership changes now end them and tell the client (`SUBSCRIPTION_REVOKED`).

**Open:**

| Issue | Planned fix |
|---|---|
| Server-topic events leak board metadata to members who cannot view the board | 6 |
| Realtime tickets live in memory (single instance only) | Move to DB or Redis before scaling out |
| No rate limiting on auth and uploads | Add a limiter |
| CORS origins are hardcoded (the WebSocket origins are already configurable) | Make them configurable |
| Permission rule listing is visible to every member | Consider requiring `MANAGE_SERVER_PERMISSIONS` |
