#!/usr/bin/env bash
# Downloads AndroidUSBCamera 3.3.3 source and prepares it as local Gradle
# modules (libausbc, libuvc, libnative, libuvccommon), bypassing JitPack
# entirely. The libuvc native code ships as prebuilt .so files, so no
# ndk-build is needed; only libnative compiles a small CMake target.
set -euo pipefail

TAG="3.3.3"

echo ">> Downloading AndroidUSBCamera $TAG source from GitHub..."
curl -sSL -o /tmp/ausbc.tar.gz "https://codeload.github.com/jiangdongguo/AndroidUSBCamera/tar.gz/refs/tags/$TAG"
tar -xzf /tmp/ausbc.tar.gz -C /tmp
SRC="/tmp/AndroidUSBCamera-$TAG"

echo ">> Copying library modules into the project..."
for MOD in libausbc libuvc libnative libuvccommon; do
  rm -rf "$MOD"
  cp -r "$SRC/$MOD" .
done

echo ">> Removing native C sources from libuvc (prebuilt .so files are used)..."
rm -rf libuvc/src/main/jni

echo ">> Stripping 'package' attributes from library manifests (AGP 8)..."
for MF in libausbc libuvc libnative libuvccommon; do
  sed -i 's/ *package="[^"]*"//' "$MF/src/main/AndroidManifest.xml"
done


echo ">> Patching legacy sources for modern androidx..."
# DialogFragmentEx defines requireArguments(), which is now a FINAL method on
# androidx Fragment (added in fragment 1.1.0 with identical semantics).
# Rename it so it no longer clashes; callers resolve to the framework method.
DFE="libuvccommon/src/main/java/com/jiangdg/dialog/DialogFragmentEx.java"
if grep -q "protected Bundle requireArguments()" "$DFE"; then
  sed -i 's/protected Bundle requireArguments()/protected Bundle requireArgumentsLegacy()/' "$DFE"
  echo "   patched: DialogFragmentEx.requireArguments -> requireArgumentsLegacy"
else
  echo "   ERROR: expected requireArguments() in DialogFragmentEx.java not found (source drift?)"
  exit 1
fi

# USBMonitor registers a receiver whose filter includes a custom action
# (ACTION_USB_PERMISSION). On Android 13+ with targetSdk 33+, that throws
# SecurityException unless RECEIVER_EXPORTED / NOT_EXPORTED is specified.
USBM="libuvc/src/main/java/com/jiangdg/usb/USBMonitor.java"
OLD_CALL='context.registerReceiver(mUsbReceiver, filter);'
NEW_CALL='if (android.os.Build.VERSION.SDK_INT >= 33) { context.registerReceiver(mUsbReceiver, filter, android.content.Context.RECEIVER_EXPORTED); } else { context.registerReceiver(mUsbReceiver, filter); }'
if grep -q "$OLD_CALL" "$USBM"; then
  sed -i "s|$OLD_CALL|$NEW_CALL|" "$USBM"
  echo "   patched: USBMonitor.registerReceiver for Android 13+ (targetSdk 34)"
else
  echo "   ERROR: expected registerReceiver call in USBMonitor.java not found (source drift?)"
  exit 1
fi

# API 34 changed MediaMetadataRetriever.release() to declare 'throws IOException',
# which breaks old code calling it bare. Wrap both call sites in try/catch.
MD="libuvccommon/src/main/java/com/jiangdg/media/MediaDecoder.java"
if grep -q "mMediaMetadataRetriever.release();" "$MD"; then
  sed -i 's|mMediaMetadataRetriever.release();|try { mMediaMetadataRetriever.release(); } catch (final Exception e) { /* API 34: throws IOException */ }|' "$MD"
  echo "   patched: MediaDecoder retriever release (API 34 IOException)"
else
  echo "   ERROR: expected retriever release in MediaDecoder.java not found (source drift?)"
  exit 1
fi

MP="libuvccommon/src/main/java/com/jiangdg/media/MediaMoviePlayer.java"
if grep -q "mMetadata.release();" "$MP"; then
  sed -i 's|mMetadata.release();|try { mMetadata.release(); } catch (final Exception e) { /* API 34: throws IOException */ }|' "$MP"
  echo "   patched: MediaMoviePlayer retriever release (API 34 IOException)"
else
  echo "   ERROR: expected retriever release in MediaMoviePlayer.java not found (source drift?)"
  exit 1
fi

echo ">> Writing AGP 8 compatible build files..."

cat > libausbc/build.gradle << 'EOF'
plugins {
    id 'com.android.library'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.jiangdg.ausbc'
    // SDK 31 stubs: framework callbacks (SurfaceTextureListener etc.) are not
    // yet annotated @NonNull there, so this module's era-typical nullable
    // overrides still compile. Bytecode runs fine inside a compileSdk-34 app.
    compileSdk 31

    defaultConfig {
        minSdk 19
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
        languageVersion = '1.6'
        apiVersion = '1.6'
    }
}

dependencies {
    // Era-matched androidx versions (what this code was written against).
    // The app resolves newer versions at runtime; androidx keeps binary compat.
    implementation 'androidx.appcompat:appcompat:1.3.1'
    implementation 'androidx.constraintlayout:constraintlayout:2.0.4'
    implementation 'com.google.android.material:material:1.3.0'
    api 'com.elvishew:xlog:1.11.0'

    implementation project(':libuvc')
    api project(':libnative')
}
EOF

cat > libuvc/build.gradle << 'EOF'
plugins {
    id 'com.android.library'
}

android {
    namespace 'com.jiangdg.uvccamera'
    compileSdk 34

    defaultConfig {
        minSdk 19
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.elvishew:xlog:1.11.0'
    implementation project(':libuvccommon')
}
EOF

cat > libnative/build.gradle << 'EOF'
plugins {
    id 'com.android.library'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.jiangdg.natives'
    compileSdk 34
    ndkVersion '25.2.9519653'

    defaultConfig {
        minSdk 19
        externalNativeBuild {
            ndk {
                abiFilters 'armeabi-v7a', 'arm64-v8a'
            }
        }
    }

    externalNativeBuild {
        cmake {
            path 'src/main/cpp/CMakeLists.txt'
            version '3.22.1'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
        languageVersion = '1.6'
        apiVersion = '1.6'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
}
EOF

cat > libuvccommon/build.gradle << 'EOF'
plugins {
    id 'com.android.library'
}

android {
    namespace 'com.jiangdg.common'
    compileSdk 34

    defaultConfig {
        minSdk 19
        buildConfigField "String", "STL_NAME", "\"c++_shared\""
    }

    buildFeatures {
        buildConfig true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.legacy:legacy-support-v4:1.0.0'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.preference:preference:1.2.1'
}
EOF

echo ">> AUSBC modules ready:"
ls -d libausbc libuvc libnative libuvccommon
