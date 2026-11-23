# Use BuzzKill rules with Air Relay

A matching BuzzKill rule runs a Tasker task. Tasker posts a local notification
that Air Relay turns into a vibration through its existing relay queue.

1. In Tasker, create a task named **Vibrate Fitbit Air** and add a **Notify**
   action. Set the title to `Air Relay trigger` and the text to `%TIMEMS`.
   Leave **Permanent** off. Use a dedicated silent notification category if you
   want the phone to stay quiet. Keep the category enabled so Android posts the
   notification. Allow Tasker to post notifications if prompted.
2. In Air Relay, turn on **Tasker** under **Rules → Your apps**, choose its
   vibration, and start the relay. Run the task in Tasker and look for **Sent**
   under **Recent activity**. If it says **Quiet · Phone awake**, turn off the
   sleep rule while you test.
3. Enable **Allow External Access** in Tasker. In BuzzKill, create a rule with
   the conditions you want and choose **Trigger Tasker**. Select **Vibrate
   Fitbit Air**. Turn off direct Air Relay rules for apps handled by BuzzKill
   to avoid two vibrations for the same alert.

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
