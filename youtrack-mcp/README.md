# youtrack-mcp

An [MCP](https://modelcontextprotocol.io) server that lets an AI assistant such as Claude Code
work with YouTrack issues: find them, read them, create them, comment on them and update them.
It's written in Java on the official MCP Java SDK and runs locally over stdio.

The server talks to one YouTrack instance (Cloud or self-hosted) with one permanent token. The
token stays inside the server process: it never goes to the model, into tool output or into the
logs.

## Tools

| Tool | What it does | Kind |
|---|---|---|
| `list_projects` | Lists the projects the token can see (archived ones left out), with their keys. | read |
| `search_issues` | Runs a YouTrack search query and returns up to 50 issues, one line each: ID, summary and every custom field that's set. | read |
| `get_issue` | Returns one issue in full: dates, custom fields, links to other issues (such as `subtask of ABC-1`), description and comments. | read |
| `create_issue` | Creates an issue from a project key, a one-line summary and an optional Markdown description. | write |
| `add_comment` | Adds a Markdown comment to an issue. | write |
| `update_issue` | Applies a YouTrack command, the same text as in YouTrack's command dialog, such as `State In Progress Assignee me`. Lists what changed. | write |
| `update_summary_and_description` | Replaces an issue's summary, description or both; whatever is left out stays as it is. The description is Markdown. | write |

Some behaviour worth knowing:

- **Project keys aren't assumed.** When the user hasn't said which project, the model is told to
  call `list_projects` and ask rather than guess.
- **Commands are all-or-nothing.** If any part of an `update_issue` command is invalid, YouTrack
  changes nothing and the tool says so. Commands can also add links, such as `subtask of ABC-1`.
- **Write failures say what to check.** If YouTrack can't be reached after a write was sent, the
  tool says the change *may* have happened and how to check, so the model doesn't blindly retry
  and create duplicates.
- **Not supported:** deleting anything, attachments, comment visibility, editing a description
  in place (`update_summary_and_description` replaces it whole), and setting custom fields on
  create (set them afterwards with `update_issue`).

## Requirements

- JDK 25
- Maven
- A YouTrack instance and a permanent token for it

## Build

```sh
cd youtrack-mcp
mvn package
```

This writes a runnable jar to `target/youtrack-mcp.jar`. `mvn verify` also runs the integration
tests, which start the jar against a fake YouTrack (WireMock). No real instance is needed to
build or test.

## Configuration

The server reads its configuration from environment variables only:

| Variable | Required | Meaning |
|---|---|---|
| `YOUTRACK_BASE_URL` | yes | The instance's base URL, such as `https://example.youtrack.cloud`, or `https://tracker.example.com/youtrack` for a self-hosted instance with a path prefix. `http` or `https`; no query or fragment. |
| `YOUTRACK_TOKEN` | yes | A YouTrack permanent token. |
| `LOG_DIR` | no | Where the log file goes. Default: `~/Library/Logs/youtrack-mcp` (the macOS convention; set it on other systems). |

If a required variable is missing or the base URL is invalid, the server logs the reason and
exits at start-up.

### Getting a token

In YouTrack, open your avatar → **Profile** → **Account Security**, and under **Tokens** create a
new token with the YouTrack scope. Copy it straight away: YouTrack shows it only once. The server can see and change exactly what the token's user can, so prefer
least privilege: ideally a dedicated YouTrack user with access only to the projects the
assistant should work in.

### Keeping the token out of config files

How the variables reach the server is up to you. Whatever you choose, avoid putting the token
value itself in a file that gets committed or synced. Some options:

- **A launch wrapper (recommended).** Register a small script as the MCP command. It fetches the
  token from your secret store and then starts the server:

  ```sh
  #!/bin/sh
  # youtrack-mcp.sh
  export YOUTRACK_BASE_URL="https://example.youtrack.cloud"
  # Pick the line for your secret store:
  export YOUTRACK_TOKEN="$(security find-generic-password -s youtrack-mcp -w)"   # macOS Keychain
  # export YOUTRACK_TOKEN="$(pass show youtrack/token)"                         # pass
  exec java -jar /path/to/youtrack-mcp.jar
  ```

  With 1Password, `op run` can do the same job: keep an env file of `op://` references and
  wrap the command with `op run --env-file=<file> -- java -jar /path/to/youtrack-mcp.jar`.
- **Variable expansion in `.mcp.json`.** Claude Code expands `${VAR}` in a project's
  `.mcp.json`, so the file can say `"YOUTRACK_TOKEN": "${YOUTRACK_TOKEN}"` while the value
  lives in your own environment.
- **Don't use `claude mcp add -e YOUTRACK_TOKEN=...`.** It stores the literal value in Claude
  Code's config file, and the command line in your shell history.

Other MCP clients have their own way of setting a server's environment; see their
documentation.

## Registering with Claude Code

With a launch wrapper:

```sh
claude mcp add youtrack -- /path/to/youtrack-mcp.sh
```

Then run `/mcp` in Claude Code to check that the server connected and lists its seven tools. A
stdio server inherits Claude Code's environment, so if you set the variables in your shell
instead, start `claude` from that shell.

### Permissions

Let the read tools run freely and have the write tools ask first. In Claude Code's settings:

```json
{
  "permissions": {
    "allow": [
      "mcp__youtrack__list_projects",
      "mcp__youtrack__search_issues",
      "mcp__youtrack__get_issue"
    ]
  }
}
```

The tool names follow the server name you registered (`youtrack` above). Leave
`create_issue`, `add_comment`, `update_issue` and `update_summary_and_description` out of the allow
list. Also, at the permission
prompt, choosing "don't ask again" adds a tool to that list.

## Project keys

`list_projects` returns whatever the token can see, and the model asks the user when the project
is ambiguous. To skip that step in the usual case, name each repository's YouTrack project in its
`CLAUDE.md`, for example "Issues for this repo are in YouTrack project ABC." Nothing in the server
is tied to a particular instance or project.

## Logging

Logs go to stderr and to `youtrack-mcp.log` in `LOG_DIR`. Stdout carries only the MCP protocol.
The log records issue IDs and errors, but never the token, and never issue summaries,
descriptions, comments or commands, since those may hold private details.
