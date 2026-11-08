# Security Policy

SIES Attendance is an **unofficial** attendance system for SIES College of Arts, Science & Commerce, Sion (West), Mumbai, built and maintained primarily by Wrichik Basu, a student of the Department of Computer Science. It handles personal data about real students and staff, so security reports are taken seriously and are welcome.

## Supported versions

Fixes are made on the `main` branch and shipped in the next release. Older versions do not receive patches.

| Version | Supported |
| --- | --- |
| Latest release and `main` | Yes |
| Any earlier release | No |
| 1.3.0 and earlier (offline only, no server) | No |

If you find an issue in an old version, please check whether it still exists on `main` before reporting.

## Reporting a vulnerability

Please do not open a public issue, discussion or pull request for a security problem.

Report it privately through GitHub instead:

1. Go to the [Security tab](https://github.com/WrichikBasu/SIES_Attendance/security) of this repository.
2. Click "Report a vulnerability".
3. Fill in the form.

If you cannot use GitHub's reporting form, email `basulabs[dot]developer[at]gmail[dot]com` instead, with "SIES Attendance security" in the subject line.

A useful report includes:

- which component is affected (`:app`, `:server` or `:shared`) and the version or commit hash
- what an attacker could do, and which role they would need (none, student, class representative, professor, head of department, office/admin)
- steps to reproduce, ideally against a local test server
- any proof of concept, logs or screenshots, with personal data removed

You do not need a fix to report something. A clear description is enough.

## What happens next

This project is maintained by students alongside coursework, so I shall keep you updated about the timeline through the private advisory thread, or by email if that is how you reported. Once a fix is released, the advisory is published on GitHub, and you will be credited by name or handle unless you would rather stay anonymous.

Please keep the details private until the advisory is published, or until 90 days have passed since your report, whichever comes first. 

## Scope

In scope:

- Authentication and session handling: sign-in, password changes, token handling.
- Authorisation: any way for a user to read or change data outside their role, including bypassing PostgreSQL row-level security or the permission checks in the API.
- The `/api/v1` API and the server web console: injection, broken access control, data leaks.
- Sync between the app and the server, including certificate pinning in release builds.
- Data stored on the device by the Room database, and exported or imported files (`.ods`, CSV, attendance sheets, backups).
- Exposure of student or staff personal data such as names, email addresses, roll numbers or APAAR IDs.
- Secrets or credentials committed to the repository.
- Vulnerable dependencies, where the vulnerable code is actually reachable in this project.

Out of scope:

- Debug builds. They allow plain HTTP to a local server on purpose.
- Deployments where the server is misconfigured by whoever runs it, unless the default configuration or the documentation leads to the problem.
- Attacks that need a rooted phone, physical access to an unlocked phone, or a compromised server operating system.
- College systems, networks or websites that are not part of this repository.
- Volumetric denial of service, spam and social engineering.
- Reports produced only by automated scanners, without a demonstrated impact.

## Testing rules

Please test against your own local build and your own server. Do not test against a server holding real college data.

If you come across real personal data by accident:

- stop at the minimum needed to show the problem
- do not copy, keep or share the data
- say in your report what you saw

Good-faith research that follows this policy will not be treated as an attack, and I will not ask anyone to take action against you over it.

## Not affiliated with the college

This project is not an official product of SIES College or its IT department. For problems with official college systems, contact the college directly.
