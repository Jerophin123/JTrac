import { describe, expect, it } from 'vitest';
import { agentGuides } from './agentSetup';

const URL = 'https://tracker.example.com/mcp/c_abcdefghijklmnopqrstuvwxyz012345';

describe('agentGuides', () => {
  const guides = agentGuides(URL);

  it('covers the major coding agents and a generic MCP client', () => {
    expect(guides.map((g) => g.name)).toEqual(
      expect.arrayContaining(['Claude Code', 'OpenAI Codex CLI', 'Gemini CLI', 'Google Antigravity', 'OpenCode', 'Cursor', 'Manus', 'Other MCP clients']),
    );
  });

  it('embeds only the connection URL, never a credential', () => {
    for (const guide of guides) {
      const code = guide.steps.map((s) => s.code ?? '').join('\n');
      expect(code, guide.name).toContain(URL);
      expect(code.toLowerCase(), guide.name).not.toMatch(/bearer|password|secret|api[_-]?key|jtrac_/);
    }
  });

  it('produces valid JSON for file-based configurations', () => {
    for (const guide of guides) {
      for (const step of guide.steps) {
        if (step.code?.trimStart().startsWith('{')) {
          expect(() => JSON.parse(step.code ?? '') as unknown, `${guide.name}: ${step.text}`).not.toThrow();
        }
      }
    }
  });

  it('uses each agent’s documented configuration keys', () => {
    const code = (id: string) => (guides.find((g) => g.id === id)?.steps ?? []).map((s) => s.code ?? '').join('\n');
    expect(code('claude-code')).toContain(`claude mcp add --transport http jtrac ${URL}`);
    expect(code('codex')).toContain(`codex mcp add jtrac --url ${URL}`);
    expect(code('gemini-cli')).toContain('"httpUrl"');
    expect(code('antigravity')).toContain('"serverUrl"');
    expect(code('opencode')).toContain('"type": "remote"');
    expect(code('vscode')).toContain('"type": "http"');
  });
});
