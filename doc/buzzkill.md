# Use BuzzKill rules with Air Relay

A matching BuzzKill rule runs a Tasker task. Tasker posts a local notification
that Air Relay turns into a vibration through its existing relay queue.

1. In Tasker, create a task named **Vibrate Fitbit Air** and add a **Notify**
   action. Set the title to `Air Relay trigger` and the text to `%TIMEMS`.
   Leave **Permanent** off. Use a dedicated silent notification category if you
   want the phone to stay quiet. Keep the category enabled so Android posts the
   notification. Allow Tasker to post notifications if prompted.
2. In Tasker preferences, enable **Allow External Access**. In BuzzKill, create
   a rule with the conditions you want and choose **Trigger Tasker**. Select
   **Vibrate Fitbit Air**.
3. In Air Relay, turn on **Tasker** under **Rules → Your apps**, choose its
   vibration, and start the relay. Turn off direct Air Relay rules for apps
   handled by BuzzKill so the original alert cannot also cause a vibration.
4. Run the task in Tasker once to check the connection, then trigger a matching
   notification to check the BuzzKill rule. With the relay running, both should
   vibrate your Air. Pause the relay and repeat to check that it stays quiet.

## Test without BuzzKill

Use a build of Air Relay that includes the bridge. If you don't already have
Tasker, the developer offers a [seven-day trial](https://tasker.joaoapps.com/download.html).
Create the **Notify** action from step 1 and select Tasker in Air Relay as in
step 3. Open the task in Tasker and tap **Play**. You can leave **Allow External
Access** off for a manual run.

Check that a Tasker entry appears in **Recent activity** and that the paired Air
vibrates. Let the first vibration finish, then run the same task again to check
that replacing the notification triggers another vibration. Repeat with Air
Relay paused to check that the Air stays quiet. This exercises the same
Tasker-to-Air Relay handoff that a matching BuzzKill rule would use.

Use the same title for every run. Tasker replaces the existing notification,
and the timestamp lets Air Relay recognize a new run without vibrating twice
for a redelivered update. Rapid alerts may be combined by the relay. Quiet hours
and pause apply. Ordinary Tasker notifications still follow the normal rules
for selected apps. To turn off the bridge, deselect Tasker or disable the
BuzzKill rule.

BuzzKill shares the original notification fields with Tasker when you use its
Tasker action. The task above sends Air Relay only the timestamp, and its
signal shows up as Tasker in Recent activity.

[BuzzKill setup](https://buzzkillapp.com/help/how-to-setup-tasker/) ·
[BuzzKill privacy policy](https://samruston.com/buzzkill/policy) ·
[Tasker Notify action](https://tasker.joaoapps.com/userguide/en/help/ah_notification.html)
