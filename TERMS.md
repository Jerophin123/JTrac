# JTrac Terms and Conditions

*Last updated: 26 October 2026*

These Terms and Conditions ("Terms") apply to the JTrac source code, documentation, release
packages and other materials published in the repository at https://github.com/Jerophin123/JTrac (together, "the
Software"). They complement, and never limit, the rights you receive under the
[Apache License, Version 2.0](LICENSE).

> **Short version.** You may use, copy, modify and distribute JTrac freely under the Apache
> License 2.0. It comes with no warranty. If you run JTrac for other people, *you* are the
> operator: you are responsible for their data, for your configuration, and for complying with
> the laws that apply to you.

## 1. Who these Terms are between

The Software is published by Jerophin D R ("the Maintainer") and contributors. "You" means any
person or organization that downloads, uses, modifies, hosts or distributes the Software.
An "Operator" is anyone who runs an instance of JTrac that other people use.

## 2. License

The Software is licensed under the Apache License, Version 2.0. In case of any conflict
between these Terms and the Apache License, the Apache License prevails for the copyright and
patent rights it grants. Nothing in these Terms restricts rights granted by that License.

Release packages bundle third-party components under their own licenses (see [NOTICE](NOTICE)
and `licenses/` in each package). Your use of those components is governed by their licenses.

## 3. No warranty

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE
AND NON-INFRINGEMENT. The Maintainer does not warrant that the Software is free of defects,
secure against every attack, or suitable for any particular purpose, or that its documentation
(including the bundled Terms of Service and Privacy Notice templates) is legally sufficient for
your situation.

Platform test status is published honestly in
[docs/platform-support.md](docs/platform-support.md). A platform marked as untested has not
been verified.

## 4. Limitation of liability

TO THE MAXIMUM EXTENT PERMITTED BY LAW, IN NO EVENT SHALL THE MAINTAINER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY OR CONSEQUENTIAL DAMAGES
(INCLUDING LOSS OF DATA, PROFITS, GOODWILL OR BUSINESS INTERRUPTION) ARISING IN ANY WAY OUT OF
THE USE OF THE SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. Some jurisdictions
do not allow these exclusions; in that case liability is limited to the smallest extent the
law permits.

## 5. Your responsibilities as an Operator

If you run JTrac for anyone other than yourself, you are responsible for:

1. **Personal data.** JTrac stores account details, content and activity of its users. You are
   the data controller (the *Data Fiduciary* under India's Digital Personal Data Protection
   Act, 2023, or the equivalent role under GDPR and other laws). The Maintainer never receives
   any data from your instance.
2. **Legal pages.** JTrac ships *templates* of a Terms of Service, a Privacy Notice and a
   Grievance Redressal page. Fill in your operator details (`jtrac configure legal`) and have
   them reviewed by a lawyer before relying on them.
3. **Security.** Keep the Software and its host updated, use HTTPS in front of it, protect the
   data directory and backups (they contain secrets), and follow [docs/security.md](docs/security.md).
4. **Email.** Use an SMTP account you are authorized to send from; respect anti-spam laws.
5. **Lawful use.** Do not use the Software to process data or content unlawfully.

## 6. Acceptable use of the project spaces

In the repository, issues, discussions and pull requests:

- follow the [Code of Conduct](CODE_OF_CONDUCT.md);
- don't post personal data, credentials, or exploit details for unpatched vulnerabilities
  (report those privately: [SECURITY.md](SECURITY.md));
- don't submit content you don't have the right to license under Apache 2.0.

## 7. Contributions

By submitting a contribution (code, documentation, translations or other material) you agree
that it is licensed under the Apache License 2.0 as described in its Section 5, and you
confirm you have the right to submit it. See [CONTRIBUTING.md](CONTRIBUTING.md).

## 8. Trademarks and names

"JTrac" refers to this project and to the original JTrac issue tracker whose concepts inspired
it. The Apache License does not grant permission to use the project's names or logos to imply
endorsement of a modified version or a service. Distribute modified versions under a name that
makes clear they are modified. Third-party names (Java, PostgreSQL, Spring, React, GitHub,
Zoho, Microsoft and others) belong to their owners and are used only to describe compatibility.

## 9. Third-party services

The Software talks only to services *you* configure: your PostgreSQL server, your SMTP
provider, and webhook endpoints set up by your administrators. The Maintainer has no control
over, and is not responsible for, those services.

## 10. Export and sanctions

You are responsible for complying with export-control and sanctions laws that apply to you
when downloading, using or distributing the Software, including its cryptographic features
(TLS, Argon2id password hashing, AES encryption of webhook secrets).

## 11. Changes to these Terms

These Terms may be updated; the date at the top changes when they do, and the history is
visible in the repository. Changes never retroactively reduce rights granted by the Apache
License for versions you already received.

## 12. Contact

Questions about these Terms: open an issue at https://github.com/Jerophin123/JTrac/issues (no personal data, please).
Security matters: see [SECURITY.md](SECURITY.md).
