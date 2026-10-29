# jenkins-atom-mail

The [mailer](https://plugins.jenkins.io/mailer/) plugin (≈244k installations) as an **atom**, with the part of
[email-ext](https://plugins.jenkins.io/email-ext/) (≈205k) that people actually use: a subject/body template, an HTML
option, and attachments.

* `subject` / `body` — `${ENV}` expanded (`JOB_NAME`, `BUILD_NUMBER`, `GIT_COMMIT`, …).
* `html` — send the body as `text/html`.
* `attachments` — workspace-relative paths; missing files are named in the error, not a stack trace.
* `smtpHost` / `smtpPort` / `tls`, plus `user` / `password` as Jenkins credentials ids.
* `dryRun` — build the message and keep `message.eml` as an artifact without sending: that is what the smoke test
  asserts, so CI needs no SMTP server.

Delivery is `curl` over SMTP (`smtps://` when `tls` is on), which every agent has; the MIME is built here, so there is
no `javax.mail` in the package.

## What it does not do

email-ext's triggers ("only when the build fails", "every 5th build"), its Groovy/Jelly templates, and its recipient
providers. In a pipeline those are the `post { failure { … } }` block around the step — which is where they belong.

## Build and smoke test

```bash
./build.sh             # → dist/mail/{mail.jar,task.json}
./tools/smoke.sh       # text+attachment, encoded non-ASCII subject, no recipients, missing attachment
```

## Publish

```bash
tools/publish-atom.sh --center <center-dir> --atom-dir dist/mail --version 1.0.0 \
    --key key.pem --key-id <keyId>     # tools/ 来自 jenkins-atom-plugin 仓库
```
