# Building StutterWatch

Requirements: JDK 25, Gradle 9.3+ (or generate a wrapper).

    gradle wrapper --gradle-version 9.3.0   # once, if you have no gradlew
    ./gradlew build                          # Windows: gradlew.bat build

The jar appears in build/libs/stutterwatch-0.1.0.jar (ignore the -sources jar).
Copy it into %APPDATA%\.minecraft\mods along with Fabric API 0.165.0+26.3.
Then check logs/latest.log for lines starting with "[stutterwatch]".

## No JDK 25? Let GitHub build it for you

1. Create a new GitHub repo and upload this whole folder (keep the hidden .github folder).
2. Open the repo's Actions tab, wait for the "build" run to finish (about 2 minutes).
3. Open the run and download the "stutterwatch-jar" artifact. Unzip it to get the .jar.
