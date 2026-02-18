# Migration of Instant and Clock

## History

Before kotlin 2.0, there was an excellent library, kotlinx.datetime, which was widely used everywhere, also in Lyng and its dependencies.

When Kotlin 2.0 was released, or soon after, JetBrains made a perplexing decision to remove `Instant` and `Clock` from kotlinx.datetime and replace it with _yet experimental_ analogs in `kotlin.time`.

The problem is, these were not quite the same (these weren't `@Serializable`!), so people didn't migrate with ease. Okay, then JetBrains decided to not only deprecate it but also make them unusable on Apple targets. It sort of split auditories of many published libraries to those who hate JetBrains and Apple and continue to use 1.9-2.0 compatible versions that no longer work with Kotlin 2.2 on Apple targets (but work pretty well with earlier Kotlin or on other platforms). 

Later JetBrains added serializers for their new `Instant` and `Clock` types, but strangely not in the stdlib, but in newer versions of `kotlinx.serialization`. This means that plain upgrade of dependencies to 2.2 is not enough to make them work.

## Solution

We hereby publish a new version of Lyng, 1.0.8-SNAPSHOT, which uses `kotlin.time.Instant` and `kotlin.time.Clock` instead of `kotlinx.datetime.Instant` and `kotlinx.datetime.Clock`. It is in other aspects compatible also with Lynon encoded binaries. You might need to migrate your code to use `kotlin.time` types. (LocalDateTime/TimeZone still come from `kotlinx.datetime`.)

So, if you are getting errors with new version, please do:

- upgrade to Kotlin 2.2
- upgrade to Lyng 1.0.8-SNAPSHOT
- replace in your code imports (or other uses) of `kotlinx.datetime.Clock` to `kotlin.time.Clock` and `kotlinx.datetime.Instant` to `kotlin.time.Instant`.

This should solve the problem and hopefully we'll see no more such "brilliant" ideas from IDEA ideologspersons.

Sorry for inconvenience and send a ray of hate to JetBrains ;)
