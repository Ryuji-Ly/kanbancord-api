# KanbanCord API

**KanbanCord** is a free kanban board for Discord: a Trello-style task board and shared to-do list that lives in your
Discord server. Teams, clubs, study groups and game-dev communities plan their work where they already talk: create
tasks, move them across columns, assign people and roles, set due dates, labels and priorities, and get updates in
their channels, without leaving Discord.

This repository is the **API**: the back end that the Discord bot and the website both talk to. It owns every board,
task and setting, decides who may do what, and works out who should hear about each change.

| Repository | What it is |
| --- | --- |
| [kanbancord-bot](https://github.com/Ryuji-Ly/kanbancord-bot) | The Discord bot: slash commands, buttons, board posts, update feeds and direct messages. |
| [kanbancord-frontend](https://github.com/Ryuji-Ly/kanbancord-frontend) | The website at [kanbancord.com](https://kanbancord.com): whole boards with drag and drop, settings, permissions and guides. |
| **kanbancord-api** (this one) | The API both of them use. |

**Try it:** add the bot from [kanbancord.com](https://kanbancord.com), or join the
[support server](https://discord.gg/SDr4ujFPGR).

## What the API does

- **Boards, columns and tasks**, with assignees (people and roles), labels, priority levels, due dates, comments,
  checklists, and images and videos (stored on Imgur).
- **Permissions that follow Discord.** Access is resolved from the server's Discord roles and permissions, with
  optional rules per role, per person and per board on top, and a "check access" endpoint that explains every decision.
  See [how permissions are resolved](https://kanbancord.com/guides/roles-and-permissions).
- **Discord sync.** The bot keeps servers, members, roles and channels in step with Discord through internal endpoints;
  Discord stays the source of truth for who is who.
- **Notifications.** Every change is recorded in an audit log and routed to update feeds, the audit log channel and
  direct messages, by each feed's events and mentions and each person's preferences. The bot claims and delivers them.
- **Board posts and task threads**: boards shown live in a Discord message, and a thread per task, managed with the bot.
- **Feature suggestions.** Boards in simple mode that do a feature by hand (tags like `[M]` in titles, `P1`, deadlines
  or names written into tasks) get a suggestion to switch that feature on, worked out from the board when asked. Only
  dismissals are stored, per board.
- **Live updates** to the website over WebSockets, so changes from Discord appear at once.
- **Sign-in with Discord** (OAuth2), with short-lived access tokens and rotating sessions.

## Stack

Java 21 · Spring Boot 3.5 · PostgreSQL · Flyway migrations · Spring Security · WebSockets (STOMP) · JUnit with
Testcontainers.

How the parts fit together is described in [docs/SYSTEM_DESIGN.md](docs/SYSTEM_DESIGN.md).

## Running it locally

You need Java 21, a PostgreSQL database, and a Discord application (for sign-in and the bot).

```bash
./mvnw spring-boot:run
```

It listens on port 8080. Configuration comes from environment variables:

| Variable | |
| --- | --- |
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | The PostgreSQL database. Required; migrations run on start. |
| `KANBANCORD_DISCORD_CLIENT_ID`, `KANBANCORD_DISCORD_CLIENT_SECRET` | The Discord application, for signing in on the website. |
| `KANBANCORD_JWT_SECRET` | Signs access tokens; at least 32 bytes. Without it, signing in is disabled. |
| `KANBANCORD_TOKEN_ENCRYPTION_KEY` | Optional: encrypts stored Discord tokens (32 bytes, base64). Derived from the JWT secret if left out. |
| `KANBANCORD_BOT_TOKEN` | The shared secret the bot sends on internal endpoints (the bot's `KANBANCORD_INTERNAL_SYNC_TOKEN`). |
| `KANBANCORD_IMGUR_CLIENT_ID` | Optional: enables image and video uploads. |
| `KANBANCORD_CORS_ALLOWED_ORIGINS`, `KANBANCORD_REALTIME_ALLOWED_ORIGINS` | Optional: where the website runs. Default to localhost and kanbancord.com. |
| `KANBANCORD_SESSION_COOKIE_SECURE` | Optional: set to `false` for local development over plain HTTP. |
| `KANBANCORD_RATE_LIMIT_ENABLED` | Optional: rate limiting, on by default. |
| `KANBANCORD_ROADMAP_BOARD_ID` | Optional: the board shown to everyone at kanbancord.com/roadmap. Anyone signed in can add one issue an hour to its first column, whatever it is called. Off when unset. |
| `KANBANCORD_DB_POOL_SIZE` | Optional: database connections, 20 by default. Requests run on virtual threads and wait for a connection when all are busy. |
| `KANBANCORD_PERFORMANCE_LOG_MS` | Optional: how often a line on requests, database connections and memory is logged; every 15 minutes by default, 0 for never. |
| `JAVA_TOOL_OPTIONS` | Optional: JVM settings, such as `-XX:MaxRAMPercentage=50` for the heap. Without a container memory limit the percentage is of the whole machine. |

In production the API runs behind nginx and Cloudflare; `/api/internal/*` is only reachable by the bot.

## Tests

```bash
./mvnw test
```

The end-to-end tests start a real PostgreSQL in Docker through Testcontainers, so Docker must be running.

Pushes to `main` build a Docker image and publish it to the GitHub Container Registry.

## Backlog

### Planned

- **Public documentation** of how KanbanCord works and how to use it.
- **Lazy member storage.** Only people who use KanbanCord, or are assigned, are kept; others are looked up in Discord
  when needed, such as when searching for someone to assign.
- **Removing orphaned users**: people no longer in any server with the bot.
- **Per-assignee completion.** For tasks everyone has to do individually, each assignee marks their own part done, and
  the task shows who is still pending.
- **A one-line summary for a label**: how many of its tasks are done and who still has open ones, at a glance.
- **Import from other tools**: Trello, Jira and similar exports, and Excel files directly (lists, CSV and pasted
  spreadsheet cells can already be imported).
- **GitHub integration**, with two kinds of sync a board can use, each first one way and then both ways. Teams
  already plan sprints in GitHub (the pothole detection team keeps its code there).
  - *Repository issues:* new issues in a linked repository become tasks in the board's chosen issues column, and
    their title, body, labels (matched by name) and open or closed state follow them; a merged pull request that says
    "Fixes #12" closes the task too. Later both ways: editing, moving or closing the task updates the issue (closing it
    in the last column, reopening it when moved back).
  - *GitHub Projects:* a board kept in step with the contents of a GitHub Project that has a kanban board, or something
    like one. A Project's board, table and roadmap are only views of the same items and fields, so what is synced is
    the items (issues, pull requests and draft items, in their order) and the single-select field a board view groups
    them by, Status unless the view says otherwise: its options are the columns. With several such fields, the one to
    use is chosen when linking; a Project with none has nothing to map columns to and is not offered. Views
    themselves (filters, sorting, hidden columns) are not copied. First one way, items added, moved, edited or removed
    in the Project change the board; then both ways, moving, editing or adding a task updates the Project (that
    field, the title, or a new draft item).
  - Every change records where it came from, so a change made by a sync is never sent back. Tasks link to their issue
    or item.
  - *Connecting:* a GitHub App (REST for issues, GraphQL for Projects), with webhooks verified by their signature. Only
    the installation, the repository or Project and the issue or item ids are stored. GitHub accounts are not linked to
    Discord ones at first, so assignees are left out.
  - *Delay disclaimer:* GitHub only sends live events for Projects owned by an organisation (verify in GitHub's current
    docs). A Project owned by a personal account is checked every few minutes instead, so changes made there reach the
    board a few minutes late; the board's settings say so when such a Project is linked. Repository issues arrive live.
  - Set up from the board's settings and with a `/board github` command, for Discord parity.
  - **Ship it on a feature branch and merge it at a quiet time** (early morning UTC, the quietest hours in the
    dashboard's heatmap), not in the evening when most people use KanbanCord: it will need fixes after it goes live.
- **A Redis message queue**, so real-time updates reach everyone whichever API instance they are connected to.
- **Multiple API instances.**

### Maybe

- **Reports as tasks.** Bug reports and suggestions sent with `/report` added straight to the Issues column of
  KanbanCord's own board.
- **API access for other services.** Personal tokens to use the API from your own scripts and tools, limited to the
  servers, boards and permissions they were granted for, and integrations built on them (Slack, Jira and similar;
  GitHub is planned above), so that something happening there creates a task automatically, under conditions you set.
- **Turning hand-written tags into labels and priorities.** After labels or priorities are switched on from a
  suggestion, offer to create them from the tags found (`[M]`, `[E]`), apply them and remove the tags from titles, with
  a preview, a confirmation and an audit log entry. Waiting for a decision.
- **Discord scheduled events for due dates**: a task's due date shown as an event in the server.
- **Active boards across servers** in the dashboard: one view of the boards you work on in every server. Pinned
  boards already cover quick access to them.

## License

[MIT](LICENSE) © Ryuji Ly. KanbanCord is not affiliated with or endorsed by Discord.
