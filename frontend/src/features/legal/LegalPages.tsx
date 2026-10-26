import { Link } from '@tanstack/react-router';
import { Clause, Email, GrievanceEmail, OperatorName, Value } from './legal';
import { LegalLayout } from './LegalLayout';
import styles from './LegalPages.module.css';

/*
 * The documents describe what this JTRAC build actually does with personal data. When the
 * software changes what it collects or shares, update these texts and bump jtac.legal.version
 * so every user is asked to accept the new version.
 */

export function TermsPage() {
  return (
    <LegalLayout
      title="Terms of Service"
      summary="The agreement between you and the operator of this JTRAC installation when you use it."
    >
      {(legal) => (
        <>
          <Clause id="agreement" title="1. The agreement">
            <p>
              These Terms of Service (“Terms”) govern your use of this installation of JTRAC, an issue-tracking and project
              management service operated by <OperatorName legal={legal} /> (“the Operator”, “we”, “us”). By creating an
              account, accepting an invitation or otherwise using the service you agree to these Terms and to
              the <Link to="/legal/privacy">Privacy Notice</Link>, which explains how your personal data is handled. If you
              do not agree, do not use the service.
            </p>
            <p>
              If you use JTRAC on behalf of an organization (for example your employer), you confirm that you are authorised
              to accept these Terms for it, and “you” includes that organization.
            </p>
          </Clause>

          <Clause id="eligibility" title="2. Who may use JTRAC">
            <p>
              You must be at least 18 years old and able to enter into a binding contract under the law that applies to you.
              JTRAC is a workplace tool and is not intended for children; we do not knowingly create accounts for anyone
              under 18. If you believe a child has an account, tell the grievance officer and we will close it.
            </p>
          </Clause>

          <Clause id="accounts" title="3. Your account">
            <ul>
              <li>Give accurate information when you sign up and keep your email address up to date.</li>
              <li>
                Keep your password and API tokens secret. You are responsible for activity under your account until you tell
                us it has been compromised.
              </li>
              <li>One account per person. Do not share accounts or sign in as someone else.</li>
              <li>
                Organization administrators can invite, change the role of, or remove members of their organization. Removing
                you from an organization does not delete your account.
              </li>
            </ul>
          </Clause>

          <Clause id="acceptable-use" title="4. Acceptable use">
            <p>You agree not to:</p>
            <ul>
              <li>break any law, or infringe anyone’s intellectual property, privacy or other rights;</li>
              <li>upload malware, or content that is unlawful, defamatory, obscene, harassing or hateful;</li>
              <li>
                probe, scan or test the vulnerability of the service, or circumvent authentication, rate limits or access
                controls, except in a security test the Operator has authorised in writing;
              </li>
              <li>overload the service (for example with automated requests beyond reasonable API use);</li>
              <li>access organizations, projects or data you have not been given access to;</li>
              <li>use the service to send spam or unsolicited messages, including through mentions or invitations.</li>
            </ul>
            <p>
              Content that is unlawful may be removed, and we may act on lawful orders from courts or government authorities,
              including under the Information Technology Act, 2000 and the rules made under it.
            </p>
          </Clause>

          <Clause id="content" title="5. Your content">
            <p>
              You (or the organization you work for) keep ownership of the issues, comments, attachments and other content you
              put into JTRAC (“Content”). You give the Operator a limited licence to store, copy, display and transmit Content
              only as needed to run the service for you and the people you share it with, for example to show an issue to
              members of its project or to email a notification.
            </p>
            <p>
              Content in an organization is visible to its members according to the roles and project permissions its
              administrators set. Members can export issues they can see; organization administrators can also configure
              webhooks and integrations that send Content to other services, and delete it. You are responsible for having the right to upload the Content
              you add.
            </p>
          </Clause>

          <Clause id="privacy" title="6. Privacy and personal data">
            <p>
              The Operator processes personal data as described in the <Link to="/legal/privacy">Privacy Notice</Link>, in
              line with the Digital Personal Data Protection Act, 2023 (“DPDP Act”) and the rules made under it. You can
              review your consent, download your data and withdraw consent from <em>Account → Privacy &amp; data</em>.
            </p>
            <p>
              If you put other people’s personal data into JTRAC (for example a customer’s details in an issue), you are
              responsible for having a lawful basis to do so and for keeping it to what the work needs.
            </p>
          </Clause>

          <Clause id="availability" title="7. Availability and changes to the service">
            <p>
              We aim to keep JTRAC available and secure, but the service is provided “as is” and “as available”. It may be
              interrupted for maintenance, upgrades or reasons beyond our control. Features may change or be removed. Keep your
              own copies of anything you cannot afford to lose; issues can be exported as CSV.
            </p>
          </Clause>

          <Clause id="ip" title="8. Intellectual property">
            <p>
              The JTRAC software, its name, logo and design remain the property of their respective owners. These Terms do not
              give you any right to use them except to use the service. Feedback you send may be used to improve the service
              without obligation to you.
            </p>
          </Clause>

          <Clause id="termination" title="9. Suspension and termination">
            <p>
              You may stop using JTRAC at any time and ask for your account to be erased from <em>Account → Privacy &amp; data</em>.
              We may suspend or close an account that breaches these Terms, puts the service or other users at risk, or where
              the law requires it. Where reasonable we will tell you first and explain why. When an account is closed, personal
              data is handled as described in the Privacy Notice (section “How long we keep data”).
            </p>
          </Clause>

          <Clause id="liability" title="10. Disclaimers and limitation of liability">
            <p>
              To the extent the law allows, the Operator is not liable for indirect, incidental, special or consequential
              losses, or for loss of profits, revenue, data or goodwill, arising from your use of JTRAC. Nothing in these Terms
              excludes liability that cannot be excluded by law, including for fraud or for wilful misconduct.
            </p>
          </Clause>

          <Clause id="indemnity" title="11. Indemnity">
            <p>
              You agree to compensate the Operator for reasonable losses and costs arising from Content you upload or from your
              breach of these Terms or of the law, except to the extent the Operator caused them.
            </p>
          </Clause>

          <Clause id="changes" title="12. Changes to these Terms">
            <p>
              We may update these Terms, for example when the service or the law changes. The version and effective date are
              shown at the top. When the Terms or the Privacy Notice change, JTRAC asks you to review and accept the new version
              before you continue; if you do not accept, you can withdraw consent and request erasure of your account.
            </p>
          </Clause>

          <Clause id="law" title="13. Governing law and disputes">
            <p>
              These Terms are governed by the laws of India. Courts at{' '}
              <Value value={legal.jurisdiction} setting="JTRAC_LEGAL_JURISDICTION" label="Jurisdiction" /> have exclusive
              jurisdiction, subject to any right you have to approach a consumer forum or the Data Protection Board of India.
              Please raise any concern with us first through the <Link to="/legal/grievance">grievance process</Link>; most
              issues can be resolved there.
            </p>
          </Clause>

          <Clause id="contact" title="14. Contact">
            <dl className={styles.contact}>
              <dt>Operator</dt>
              <dd>
                <OperatorName legal={legal} />
              </dd>
              <dt>Address</dt>
              <dd>
                <Value value={legal.operatorAddress} setting="JTRAC_LEGAL_OPERATOR_ADDRESS" label="Postal address" />
              </dd>
              <dt>Email</dt>
              <dd>
                <Email value={legal.contactEmail} setting="JTRAC_LEGAL_CONTACT_EMAIL" label="Contact email" />
              </dd>
            </dl>
          </Clause>
        </>
      )}
    </LegalLayout>
  );
}

export function PrivacyPage() {
  return (
    <LegalLayout
      title="Privacy Notice"
      summary="What personal data JTRAC processes, why, for how long, and how you can exercise your rights under the Digital Personal Data Protection Act, 2023."
    >
      {(legal) => (
        <>
          <Clause id="who" title="1. Who is responsible">
            <p>
              <OperatorName legal={legal} /> operates this JTRAC installation and decides why and how your personal data is
              processed. Under the Digital Personal Data Protection Act, 2023 (“DPDP Act”) we are the <strong>Data Fiduciary</strong>{' '}
              and you are the <strong>Data Principal</strong>. This notice is given under section 5 of the DPDP Act and the
              Digital Personal Data Protection Rules, 2025.
            </p>
            <p>
              If you use JTRAC through an organization (for example your employer), that organization’s administrators also
              decide what goes into their workspace and who can see it.
            </p>
          </Clause>

          <Clause id="data" title="2. Personal data we process and why">
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <caption className="visually-hidden">Personal data, purpose and basis</caption>
                <thead>
                  <tr>
                    <th scope="col">Data</th>
                    <th scope="col">Purpose</th>
                    <th scope="col">Basis</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>Name, email address, handle, password (stored only as a one-way hash)</td>
                    <td>Create and secure your account, sign you in, let colleagues find and mention you</td>
                    <td>Consent</td>
                  </tr>
                  <tr>
                    <td>Organization memberships, roles, team memberships</td>
                    <td>Decide what you can see and do in each organization</td>
                    <td>Consent</td>
                  </tr>
                  <tr>
                    <td>Content you create: issues, comments, attachments, mentions, saved views, dashboards</td>
                    <td>Provide the issue-tracking service to you and your colleagues</td>
                    <td>Consent</td>
                  </tr>
                  <tr>
                    <td>Activity history and audit records (what changed, by whom, when)</td>
                    <td>Show issue history, keep the service secure, investigate misuse</td>
                    <td>Consent; legitimate uses under section 7 where the law requires records</td>
                  </tr>
                  <tr>
                    <td>Session cookie and security token</td>
                    <td>Keep you signed in and protect forms against cross-site request forgery</td>
                    <td>Necessary to provide the service you asked for</td>
                  </tr>
                  <tr>
                    <td>IP address of each request</td>
                    <td>Limit repeated sign-in and sign-up attempts; it is held in memory only and not stored</td>
                    <td>Necessary for security</td>
                  </tr>
                  <tr>
                    <td>Emails we send you (invitations, notifications, password resets)</td>
                    <td>Tell you about activity and account events; you can change notification settings</td>
                    <td>Consent</td>
                  </tr>
                  <tr>
                    <td>Consent records (version accepted, when, withdrawals)</td>
                    <td>Show that we have your consent, as the DPDP Act requires</td>
                    <td>Legal obligation</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p>
              We do not sell personal data, use it for advertising, or build profiles of you. JTRAC loads no third-party
              analytics or tracking scripts and serves its fonts itself. Your theme and last-used organization are remembered in
              your browser’s storage on your device only.
            </p>
          </Clause>

          <Clause id="sharing" title="3. Who we share it with">
            <ul>
              <li>
                <strong>Members of your organizations</strong> see your name, handle and the content you contribute, according
                to their role and project permissions.
              </li>
              <li>
                <strong>Service providers</strong> that host the servers or deliver email on our behalf, as Data Processors
                bound by contract to use the data only for us.
              </li>
              <li>
                <strong>Integrations set up by organization administrators</strong>, such as webhooks, receive issue data the
                administrator chose to send. Those services have their own privacy terms.
              </li>
              <li>
                <strong>Authorities</strong>, when the law requires it, for example under a valid court or government order.
              </li>
            </ul>
            <p>
              If personal data is transferred outside India, it is done only to countries not restricted by the Central
              Government under section 16 of the DPDP Act.
            </p>
          </Clause>

          <Clause id="retention" title="4. How long we keep data">
            <p>
              We keep personal data for as long as your account is active and you have not withdrawn consent. When you withdraw
              consent or ask for erasure, or when the purpose has been served, we erase your personal data within a reasonable
              time, unless a law requires us to keep it. Content you created in an organization may stay with that organization
              (for example an issue you reported), with your name removed where possible. Audit records needed for security or
              legal compliance are kept only as long as those reasons require.
            </p>
          </Clause>

          <Clause id="rights" title="5. Your rights">
            <p>Under the DPDP Act you can:</p>
            <ul>
              <li>
                <strong>Withdraw consent</strong> at any time, as easily as you gave it (section 6(4)). Go to{' '}
                <em>Account → Privacy &amp; data</em> and choose <em>Withdraw consent</em>. Processing done before withdrawal
                stays lawful; after it you can no longer use JTRAC.
              </li>
              <li>
                <strong>Access information</strong> about your personal data and how it is processed (section 11). Use{' '}
                <em>Download my data</em> on the same page, or write to us.
              </li>
              <li>
                <strong>Correct, complete, update or erase</strong> your personal data (section 12). You can edit your name and
                handle in your profile; for anything else, or for erasure, write to the grievance officer.
              </li>
              <li>
                <strong>Have grievances redressed</strong> (section 13). See <Link to="/legal/grievance">Grievance Redressal</Link>.
              </li>
              <li>
                <strong>Nominate</strong> another person to exercise these rights if you die or become unable to (section 14).
                Write to the grievance officer with the nominee’s details.
              </li>
            </ul>
            <p>
              To protect your account we may need to confirm your identity before acting on a request. You also have duties
              under section 15: give accurate information, and do not file false or frivolous complaints.
            </p>
          </Clause>

          <Clause id="security" title="6. How we protect your data">
            <p>
              Passwords are stored only as salted one-way hashes. Sessions use cookies that page scripts cannot read, sent only
              over HTTPS in production. Access to each organization’s data is checked on the server for every request, sign-in
              attempts are rate-limited, and changes are recorded in an audit log. No system is perfectly secure; if a personal
              data breach happens, we will inform the Data Protection Board of India and the people affected as section 8(6) of
              the DPDP Act and the rules require.
            </p>
          </Clause>

          <Clause id="children" title="7. Children">
            <p>
              JTRAC is not meant for anyone under 18, and we do not knowingly process children’s personal data. If you believe
              we have, contact the grievance officer and we will erase it.
            </p>
          </Clause>

          <Clause id="cookies" title="8. Cookies and local storage">
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <caption className="visually-hidden">Cookies and browser storage used by JTRAC</caption>
                <thead>
                  <tr>
                    <th scope="col">Name</th>
                    <th scope="col">Type</th>
                    <th scope="col">Purpose</th>
                    <th scope="col">Lifetime</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td>
                      <code>JTRAC_SESSION</code>
                    </td>
                    <td>Cookie, essential</td>
                    <td>Keeps you signed in</td>
                    <td>Until you sign out or the session expires</td>
                  </tr>
                  <tr>
                    <td>
                      <code>XSRF-TOKEN</code>
                    </td>
                    <td>Cookie, essential</td>
                    <td>Protects forms against cross-site request forgery</td>
                    <td>Session</td>
                  </tr>
                  <tr>
                    <td>
                      <code>jtrac.theme</code>, <code>jtrac.lastOrg</code>
                    </td>
                    <td>Browser storage, preference</td>
                    <td>Remember your theme and last organization on this device; never sent to the server</td>
                    <td>Until you clear your browser data</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p>JTRAC does not use advertising, analytics or social-media cookies, so there is nothing to opt out of.</p>
          </Clause>

          <Clause id="changes" title="9. Changes to this notice">
            <p>
              When this notice changes, the version and effective date above change too, and JTRAC asks you to review and accept
              the new version before you continue.
            </p>
          </Clause>

          <Clause id="contact" title="10. Contact and complaints">
            <dl className={styles.contact}>
              <dt>Grievance officer</dt>
              <dd>
                <Value value={legal.grievanceOfficerName} setting="JTRAC_LEGAL_GRIEVANCE_OFFICER_NAME" label="Grievance officer name" />
              </dd>
              <dt>Email</dt>
              <dd>
                <GrievanceEmail legal={legal} />
              </dd>
              <dt>Privacy questions</dt>
              <dd>
                <Email value={legal.contactEmail} setting="JTRAC_LEGAL_CONTACT_EMAIL" label="Contact email" />
              </dd>
              <dt>Postal address</dt>
              <dd>
                <Value value={legal.operatorAddress} setting="JTRAC_LEGAL_OPERATOR_ADDRESS" label="Postal address" />
              </dd>
            </dl>
            <p>
              If you are not satisfied with our response, you may complain to the <strong>Data Protection Board of India</strong>{' '}
              after using our grievance process, as section 13(3) of the DPDP Act provides.
            </p>
          </Clause>
        </>
      )}
    </LegalLayout>
  );
}

export function GrievancePage() {
  return (
    <LegalLayout
      title="Grievance Redressal"
      summary="How to raise a complaint or exercise your data protection rights, and what happens next."
    >
      {(legal) => (
        <>
          <Clause id="officer" title="1. Grievance officer">
            <p>
              Under section 13 of the Digital Personal Data Protection Act, 2023 and the Information Technology Act, 2000, the
              Operator has a grievance officer who answers questions and complaints about personal data and about this service.
            </p>
            <dl className={styles.contact}>
              <dt>Name</dt>
              <dd>
                <Value value={legal.grievanceOfficerName} setting="JTRAC_LEGAL_GRIEVANCE_OFFICER_NAME" label="Grievance officer name" />
              </dd>
              <dt>Email</dt>
              <dd>
                <GrievanceEmail legal={legal} />
              </dd>
              <dt>Organization</dt>
              <dd>
                <OperatorName legal={legal} />
              </dd>
              <dt>Postal address</dt>
              <dd>
                <Value value={legal.operatorAddress} setting="JTRAC_LEGAL_OPERATOR_ADDRESS" label="Postal address" />
              </dd>
            </dl>
          </Clause>

          <Clause id="how" title="2. How to raise a grievance">
            <ol>
              <li>
                Email the grievance officer from the address on your JTRAC account, so we can confirm it is you. Say what the
                problem is, which organization or issue it concerns (if any), and what you would like us to do.
              </li>
              <li>
                To withdraw consent and ask for erasure, you can also use <em>Account → Privacy &amp; data</em> in JTRAC. It
                files the request with the grievance officer and emails you an acknowledgement.
              </li>
              <li>
                To nominate someone to act for you (section 14), include their name and contact details and how they are
                related to you.
              </li>
            </ol>
          </Clause>

          <Clause id="timeline" title="3. What happens next">
            <ul>
              <li>We acknowledge your grievance, normally within a few working days.</li>
              <li>
                We respond within the period set by the Digital Personal Data Protection Rules, 2025, and in any case no later
                than 90 days from receiving it.
              </li>
              <li>If we need more information or to confirm your identity, we will ask, and tell you why.</li>
              <li>If we cannot do what you asked (for example because a law requires us to keep certain records), we explain why.</li>
            </ul>
          </Clause>

          <Clause id="escalation" title="4. If you are not satisfied">
            <p>
              Once you have used this grievance process, you may complain to the <strong>Data Protection Board of India</strong>{' '}
              under section 13(3) of the DPDP Act. Please do not file false or frivolous complaints (section 15).
            </p>
          </Clause>

          <Clause id="related" title="5. Related documents">
            <ul>
              <li>
                <Link to="/legal/privacy">Privacy Notice</Link>: what we process and your rights.
              </li>
              <li>
                <Link to="/legal/terms">Terms of Service</Link>: the rules for using JTRAC.
              </li>
            </ul>
          </Clause>
        </>
      )}
    </LegalLayout>
  );
}
