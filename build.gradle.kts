buildscript {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.10")
    }
}
plugins { id("com.android.application") version "8.10.1" apply false }
