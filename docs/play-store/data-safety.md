# Google Play: Data safety, content rating and other forms

Suggested answers, based on what the app actually does (checked against the code: permissions, storage,
network targets and dependencies). Google's definitions decide in the end, so read the questions in the console
rather than copying blindly.

## Data safety form

**Does your app collect or share any of the required user data types?** → **No**

Why: Google defines *collecting* as sending data off the device to you or to a third party. NextTask sends
nothing to the developer and contains no third-party SDKs (no analytics, ads or crash reporting). The only
network traffic goes to the Nextcloud server the user types in, which belongs to the user, not to the developer.

The form then asks for nothing else (no encryption or deletion questions apply), but it still wants:

- **Privacy policy URL**: where you host [`privacy-policy.md`](privacy-policy.md).

### If Google disagrees

I could not find a definitive statement of how Google treats a server that the user chooses. If the review or the
form asks for more, the conservative disclosure would be:

| Data type | Collected | Shared | Purpose | Optional | Encrypted in transit |
|---|---|---|---|---|---|
| Personal info → User IDs (the Nextcloud user name) | yes | no | App functionality, Account management | no | yes when `https://` is used |
| App activity → Other user-generated content (task text) | yes | no | App functionality | no | yes when `https://` is used |

With the note that the data goes to the user's own server, and that the user can delete it by signing out and
deleting the tasks on the server. Because plain `http://` can be chosen, "encrypted in transit" is honestly
*not always*; in that case answer it as not encrypted, or remove the `http://` option from the store build.

## Content rating questionnaire

Category *Utility / Productivity*. Every answer is **No**: no violence, no sexual content, no profanity, no
controlled substances, no gambling, no user-to-user communication, no sharing of location, no purchases. The
expected rating is the lowest one (everyone).

## Other declarations

| Form | Answer |
|---|---|
| Target audience and content | 18+ (not designed for children) |
| Ads | No ads |
| App access | Restricted, because a Nextcloud server is needed: give the reviewers a test server and account |
| News app / government app / financial features / health features | No |
| Sensitive permissions (location, SMS, camera, ...) | None are used |
| Advertising ID | Not used (the manifest has no `AD_ID` permission; declare "No" when asked) |
| Data safety | see above |

## Before the first upload

- Upload `NextTask-<version>.aab` from the GitHub release (see [`../releasing.md`](../releasing.md)).
- Play App Signing: by default Google holds the app signing key and the key from the release workflow becomes the
  *upload key*. APKs from GitHub are then signed with a different key than the copy Google distributes, so they
  cannot update a Play-installed copy and the other way round.
- A new personal developer account may need a closed test (reportedly 12 testers for 14 days) before the app can
  go to production; the console shows what applies to your account.
