# The externally loaded native transport resolves these classes and callbacks
# by their original JNI names, including callbacks without native declarations.
-keep class com.fitbit.goldengate.bindings.** { *; }
