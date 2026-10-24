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
| `KANBANCORD_ROADMAP_BOARD_ID` | Optional: the board shown to everyone at kanbancord.com/roadmap. Anyone signed in can add one issue an hour to its column named `KANBANCORD_ROADMAP_ISSUES_COLUMN` (default `Issues`). Off when unset. |
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
- **Rolling updates**, so deploying a new version causes no downtime.
- **Lazy member storage.** Only people who use KanbanCord, or are assigned, are kept; others are looked up in Discord
  when needed, such as when searching for someone to assign.
- **Removing orphaned users**: people no longer in any server with the bot.
- **Per-assignee completion.** For tasks everyone has to do individually, each assignee marks their own part done, and
  the task shows who is still pending.
- **A one-line summary for a label**: how many of its tasks are done and who still has open ones, at a glance.
- **An updates and announcements page** on the website.
- **Import from common formats**: CSV, Excel, or a pasted list, one task per line (other tools' exports later).
- **A Redis message queue**, so real-time updates reach everyone whichever API instance they are connected to.
- **Multiple API instances.**

### Maybe

- **Reports as tasks.** Bug reports and suggestions sent with `/report` added straight to the Issues column of
  KanbanCord's own board.
- **API access for other services.** Personal tokens to use the API from your own scripts and tools, limited to the
  servers, boards and permissions they were granted for, and integrations built on them (GitHub, Slack, Jira and
  similar), so that something happening there creates a task automatically, under conditions you set.
- **Discord scheduled events for due dates**: a task's due date shown as an event in the server.

## License

[MIT](LICENSE) © Ryuji Ly. KanbanCord is not affiliated with or endorsed by Discord.
