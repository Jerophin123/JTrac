import { faq } from '@/lib/faq';
import { pages } from '@/lib/pages';
import { absolute, seo } from '@/lib/seo';
import { downloads, latestRelease, links } from '@/lib/site';

export const dynamic = 'force-static';

/**
 * /llms.txt (llmstxt.org): a plain-text summary of the site for AI assistants and AI search,
 * built from the same data as the pages so it cannot drift.
 */
export function GET() {
  const link = (path: string) => absolute(path) ?? `/${path}`;
  const body = [
    `# ${seo.name}`,
    '',
    `> ${seo.description}`,
    '',
    `Latest release: ${latestRelease.version} (${latestRelease.url}). Unofficial, independent reimagining of the classic JTrac; not affiliated with the original project.`,
    '',
    '## Pages',
    '',
    `- [Home](${link('')}): ${seo.title}`,
    ...pages.map((p) => `- [${p.label}](${link(p.path)}): ${p.description}`),
    '',
    '## Downloads',
    '',
    ...downloads.map((d) => `- [${d.os} ${d.arch}](${d.url}): ${d.file}, about ${String(d.sizeMb)} MB`),
    '',
    '## Documentation',
    '',
    `- [Installation](${links.installation})`,
    `- [Setup wizard](${links.setupWizard})`,
    `- [Configuration reference](${links.configuration})`,
    `- [REST API (OpenAPI)](${links.openapi})`,
    `- [Source code](${links.repo})`,
    '',
    '## FAQ',
    '',
    ...faq.flatMap(({ q, a }) => [`### ${q}`, '', a, '']),
  ].join('\n');
  return new Response(body, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
}
