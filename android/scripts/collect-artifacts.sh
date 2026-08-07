#!/bin/bash -x

export BASENAME="seedkeeper-pro-android"
export FLUTTER_APK=build/app/outputs/flutter-apk
export NATIVE_LIBS=build/app/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib

rm -rf artifacts
mkdir artifacts
cp "${FLUTTER_APK}/app-arm64-v8a-release.apk"   artifacts/${BASENAME}-arm64-v8a.apk
cp "${FLUTTER_APK}/app-armeabi-v7a-release.apk" artifacts/${BASENAME}-armeabi-v7a.apk
cp "${FLUTTER_APK}/app-x86_64-release.apk"      artifacts/${BASENAME}-x86_64.apk
cp "${FLUTTER_APK}/app-release.apk"             artifacts/${BASENAME}.apk
cp build/app/outputs/bundle/release/app-release.aab artifacts/${BASENAME}.aab

cp build/app/outputs/mapping/release/mapping.txt artifacts/

pushd "${NATIVE_LIBS}/"
zip -r sym-arm64-v8a.zip arm64-v8a/*so
zip -r sym-armeabi-v7a.zip armeabi-v7a/*so
zip -r sym-x86_64.zip x86_64/*so
popd
cp "${NATIVE_LIBS}/"*zip artifacts/
