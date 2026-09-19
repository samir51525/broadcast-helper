BROADCAST HELPER  -  START HERE
================================

What it does
------------
An Android app that taps through WhatsApp for you and creates broadcast lists
from the contacts WhatsApp shows on the "New broadcast" screen (your WhatsApp
contacts), in order, top to bottom. It never puts the same contact in two lists.
Example: 1000 contacts, 250 per list, 4 lists -> it makes 4 lists.

Works with: normal WhatsApp (com.whatsapp), WhatsApp language = English.

IMPORTANT
---------
* This is automation of the WhatsApp app. WhatsApp can restrict or ban an
  account for it. Use SAFE MODE and use it only on accounts where you accept that risk.
* The code was written without being able to run it on a real phone.
  The first try may need small fixes. TEST FIRST with a small run (see Part C).


PART A - Get the APK file (one time, about 10-15 minutes, no coding)
--------------------------------------------------------------------
1. Make a free account at https://github.com  (sign up).
2. Click the "+" at the top right -> "New repository". Name it: broadcast-helper
   Choose Private. Click "Create repository".
3. On your computer, unzip this project. Inside you will see: app, build.gradle,
   settings.gradle, gradle.properties, and a hidden folder called ".github".
   (Mac: in Finder press  Command + Shift + .  to show hidden folders.)
4. On the new empty repository page click "uploading an existing file".
   Drag EVERYTHING from inside the project folder (app, .github, and the 3 files)
   into the page. Wait until the upload finishes. Click "Commit changes".
   - If the ".github" folder did not upload: click "Add file" -> "Create new file",
     type this name exactly:   .github/workflows/build-apk.yml
     then paste the contents of the file build-apk.yml from the .github/workflows
     folder, and click "Commit changes".
5. Click the "Actions" tab at the top. You will see "Build APK" running (yellow).
   Wait 3-6 minutes until it shows a green check mark.
   - If it shows a RED cross: click it, click the failed step, copy the error text
     and send it to Claude. It is usually a small fix.
6. Click the finished run. At the bottom, under "Artifacts", click
   "BroadcastHelper-APK" to download. Unzip it: you get  app-debug.apk
7. Send app-debug.apk to your phone (WhatsApp to yourself, Google Drive, USB cable...).


PART B - Install and set up on the phone
----------------------------------------
1. Tap app-debug.apk on the phone. Allow "Install unknown apps" when asked.
   If Play Protect warns, choose "Install anyway" (the app is your own build).
2. Open "Broadcast Helper".
3. Step 1 in the app: tap "Open Accessibility settings", find "Broadcast Helper"
   and turn it ON.
   - If the switch is grey (Android 13+): tap "Open App info", tap the three dots
     at the top right -> "Allow restricted settings", then go back and turn it ON.
4. Set your numbers: contacts per list (max 256) and how many lists.
5. Keep "Safe mode" selected.


PART C - First test (do this before the big run)
------------------------------------------------
1. Contacts per list: 5     Lists: 1     Safe mode.
2. Press START. WhatsApp opens and the helper taps by itself.
3. When it finishes, check WhatsApp: one new broadcast list with 5 contacts.
4. In the app press "Forget used contacts" so the real run starts from the first contact.
5. Now run the real numbers, for example 250 per list and 4 lists.

While it runs
-------------
* Don't touch the phone. Keep it unlocked and plugged in.
* To stop at any time: press a Volume button (up or down).
* The app remembers which contacts were used, so you can run it again later
  and it continues with the next unused contacts.

If something goes wrong
-----------------------
Send Claude: a screenshot of the app's Status line and a screen recording (or
screenshots) of what WhatsApp showed at the moment it stopped.
Common causes: WhatsApp is not in English, WhatsApp updated its buttons, or a
pop-up covered the screen.

Note: the new lists get WhatsApp's default names ("N recipients"). You can rename
them inside WhatsApp (open the list -> list info -> name).

Names in Hindi, Gujarati and other languages / same names
----------------------------------------------------------
Contact names can be in any script (English, Hindi, Gujarati...). The helper only
needs the WhatsApp screens themselves to be in English.

Each contact is told apart by: full name + the line under the name (status) +
which one it is among identical rows. So "Ravi Patel" and "Ravi Shah" are
different, and two different people both saved as "Ravi Patel" are also kept
apart when their status line differs or they are separate rows on the screen.
Very rare case: two contacts with the same name AND the same status split across
the top edge of the screen while scrolling - one of them may be skipped.
