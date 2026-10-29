# MobPush Runtime Dependencies

MobTech SDK binaries are not redistributed in this repository and are optional. Without them the app builds and runs normally with push notifications disabled. To enable push, obtain the official artifacts below, place them in this directory, and verify each SHA-256 digest before building. Confirm that your MobTech account and use case are licensed for these SDK versions.

| File | Source | SHA-256 |
| --- | --- | --- |
| `MobPush-3.3.9.aar` | `https://mvn.mob.com/android/com/mob/MobPush/3.3.9/MobPush-3.3.9.aar` | `2BADE8374F617EE613536DD4ACFDF4270454CF3D54E15C11EFBCC8E054D00C5C` |
| `MobCommons-2021.0601.1533.jar` | `https://mvn.mob.com/android/com/mob/MobCommons/2021.0601.1533/MobCommons-2021.0601.1533.jar` | `7E76D4A062724EECA2D6C86777302224609B3E313DABA3262B383A8AA9A092E0` |
| `MobTools-2021.0601.1530.jar` | `https://mvn.mob.com/android/com/mob/MobTools/2021.0601.1530/MobTools-2021.0601.1530.jar` | `4F60B74DF12F65400E52A6E0CF8A96728D998EB5D32D2EE0766FF51877CB6A23` |

`MobSDK.spEdition=FP` is set in `gradle.properties`. The Mob AppKey is supplied only through the Gradle property `shtong.mobPushAppKey`; no AppSecret is stored in this repository or APK configuration.
