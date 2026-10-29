/**
 * Setup instructions for connecting AI agents to a JTRAC agent connection. Only the
 * connection URL is ever embedded: agents sign in with OAuth in the browser, so no token,
 * secret or password appears in any command or configuration file.
 *
 * Syntax follows each vendor's documentation (checked October 2026). JTRAC's sign-in is tested
 * with a standards-based test client, not with each of these agents.
 */

export interface SetupStep {
  text: string;
  /** A command or file content to copy. */
  code?: string;
  /** Where the code goes (a file path), if it is file content. */
  file?: string;
}

export interface AgentGuide {
  id: string;
  name: string;
  /** How the agent receives the OAuth result. */
  callback: 'local' | 'hosted';
  steps: SetupStep[];
  notes: string[];
  docs: string;
}

/** A configuration key for the server: letters, digits, dashes. */
export const SERVER_NAME = 'jtrac';

const json = (value: unknown) => JSON.stringify(value, null, 2);

export function agentGuides(url: string): AgentGuide[] {
  return [
    {
      id: 'claude-code',
      name: 'Claude Code',
      callback: 'local',
      steps: [
        { text: 'Add the server (use --scope user to make it available in every project):', code: `claude mcp add --transport http ${SERVER_NAME} ${url}` },
        { text: 'Sign in: run /mcp inside Claude Code and choose Authenticate, or from your shell:', code: `claude mcp login ${SERVER_NAME}` },
        { text: 'Approve the request in the browser tab that opens. Claude Code stores and refreshes the tokens itself.' },
      ],
      notes: ['To share the server with a repository, add it with --scope project; .mcp.json then contains only the URL.'],
      docs: 'https://code.claude.com/docs/en/mcp',
    },
    {
      id: 'codex',
      name: 'OpenAI Codex CLI',
      callback: 'local',
      steps: [
        { text: 'Add the server:', code: `codex mcp add ${SERVER_NAME} --url ${url}` },
        { text: 'Sign in and approve the request in your browser:', code: `codex mcp login ${SERVER_NAME}` },
        { text: 'Or add it to ~/.codex/config.toml by hand:', file: '~/.codex/config.toml', code: `[mcp_servers.${SERVER_NAME}]\nurl = "${url}"` },
      ],
      notes: [],
      docs: 'https://developers.openai.com/codex/mcp',
    },
    {
      id: 'gemini-cli',
      name: 'Gemini CLI',
      callback: 'local',
      steps: [
        { text: 'Add the server:', code: `gemini mcp add --transport http ${SERVER_NAME} ${url}` },
        { text: 'Sign in from inside Gemini CLI and approve the request in your browser:', code: `/mcp auth ${SERVER_NAME}` },
        { text: 'Or add it to settings.json by hand:', file: '~/.gemini/settings.json', code: json({ mcpServers: { [SERVER_NAME]: { httpUrl: url } } }) },
      ],
      notes: ['OAuth needs a browser on the same machine; it does not work over SSH without a browser.'],
      docs: 'https://geminicli.com/docs/tools/mcp-server/',
    },
    {
      id: 'antigravity',
      name: 'Google Antigravity',
      callback: 'hosted',
      steps: [
        { text: 'Add the server to the global configuration (or <workspace>/.agents/mcp_config.json for one workspace):', file: '~/.gemini/config/mcp_config.json', code: json({ mcpServers: { [SERVER_NAME]: { serverUrl: url } } }) },
        { text: 'In Antigravity open Agent settings → Customizations and click Authenticate next to jtrac.' },
        { text: 'Approve the request in the browser, then paste the code Antigravity shows back into its settings and click Submit.' },
      ],
      notes: ['Antigravity receives the sign-in result at https://antigravity.google/oauth-callback, which JTRAC recognises by default.'],
      docs: 'https://antigravity.google/docs/mcp',
    },
    {
      id: 'opencode',
      name: 'OpenCode',
      callback: 'local',
      steps: [
        { text: 'Add the server to opencode.json (project) or ~/.config/opencode/opencode.json (global):', file: 'opencode.json', code: json({ $schema: 'https://opencode.ai/config.json', mcp: { [SERVER_NAME]: { type: 'remote', url, enabled: true } } }) },
        { text: 'Sign in and approve the request in your browser:', code: `opencode mcp auth ${SERVER_NAME}` },
      ],
      notes: ['opencode mcp list shows whether the server is authenticated.'],
      docs: 'https://opencode.ai/docs/mcp-servers/',
    },
    {
      id: 'cursor',
      name: 'Cursor',
      callback: 'local',
      steps: [
        { text: 'Add the server to ~/.cursor/mcp.json (or .cursor/mcp.json in a project):', file: '~/.cursor/mcp.json', code: json({ mcpServers: { [SERVER_NAME]: { url } } }) },
        { text: 'Open Cursor Settings → MCP, select jtrac and sign in; approve the request in your browser.' },
      ],
      notes: ['Cursor’s cloud agents use the callback https://www.cursor.com/agents/mcp/oauth/callback and must be able to reach this server over the internet.'],
      docs: 'https://cursor.com/docs/context/mcp',
    },
    {
      id: 'vscode',
      name: 'VS Code (GitHub Copilot)',
      callback: 'local',
      steps: [
        { text: 'Add the server to .vscode/mcp.json (or your user configuration):', file: '.vscode/mcp.json', code: json({ servers: { [SERVER_NAME]: { type: 'http', url } } }) },
        { text: 'Start the server from the MCP view and sign in when VS Code asks.' },
      ],
      notes: ['VS Code’s documentation does not describe dynamic client registration; if sign-in fails, use another agent.'],
      docs: 'https://code.visualstudio.com/docs/agents/reference/mcp-configuration',
    },
    {
      id: 'manus',
      name: 'Manus',
      callback: 'hosted',
      steps: [
        { text: 'In Manus open Settings → Integrations → Custom MCP Servers and click Add Server.' },
        { text: 'Enter a name (jtrac) and this URL, and choose OAuth sign-in:', code: url },
        { text: 'Approve the request in the browser window Manus opens.' },
      ],
      notes: [
        'Manus runs in the cloud: this JTRAC server must be reachable from the internet over HTTPS.',
        'Manus does not publish its OAuth callback address, so the approval screen will show it as an unrecognised website. Check that it belongs to Manus before approving.',
      ],
      docs: 'https://manus.im/docs/integrations/custom-mcp',
    },
    {
      id: 'other',
      name: 'Other MCP clients',
      callback: 'local',
      steps: [
        { text: 'Add a remote MCP server using the Streamable HTTP transport with this URL:', code: url },
        { text: 'Choose OAuth (or let the client discover it). JTRAC supports dynamic client registration, PKCE (S256) and resource indicators, as the MCP authorization specification requires.' },
      ],
      notes: ['Clients that only support static API keys cannot connect: JTRAC never accepts the connection URL or an API token as a credential here.'],
      docs: 'https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization',
    },
  ];
}
