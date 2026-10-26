/** Shared by the visible FAQ and its FAQPage structured data, so they always match. */
// Answers condensed from the FAQ in the repository README.
export const faq: { q: string; a: string }[] = [
  { q: 'Is JTRAC free?', a: 'Yes. It is open source under the Apache License 2.0: free to use, modify and distribute, commercially or not.' },
  {
    q: 'Is this the official JTrac?',
    a: 'No. It is an unofficial, independent reimagining of how JTrac could look in the modern era, inspired by JTrac 2.3.3. It is not affiliated with or endorsed by the original project, and no original code, schema or templates are reused.',
  },
  { q: 'Do I need Java installed?', a: 'No. Each package bundles its own Java 25 runtime. You only need a JDK to build JTRAC from source.' },
  {
    q: 'Do I need a PostgreSQL server?',
    a: 'Not on Windows x64, Linux and macOS: the package includes PostgreSQL and manages it for you. You can also point JTRAC at your own PostgreSQL 18 server.',
  },
  {
    q: 'Can I use MySQL, SQL Server or SQLite?',
    a: 'No. JTRAC relies on PostgreSQL 18 features and is tested only on PostgreSQL.',
  },
  {
    q: 'Does JTRAC send any data to anyone?',
    a: 'No. There is no telemetry, update check or analytics. It only talks to your database, your SMTP server and the webhook endpoints your administrators configure.',
  },
  {
    q: 'Can JTRAC run without email?',
    a: 'Yes. Messages are queued and delivered once SMTP is configured; until then invitations and password resets do not arrive, and the interface says so.',
  },
  {
    q: 'How do I put JTRAC on the internet?',
    a: 'Keep it bound to 127.0.0.1, put a reverse proxy with HTTPS in front (nginx, Caddy, IIS or Apache), then configure the public https:// address and enable the reverse-proxy setting.',
  },
];
