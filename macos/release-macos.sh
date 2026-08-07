#!/bin/sh

if ! command -v create-dmg &> /dev/null
then
	echo "create-dmg could not be found"
	exit
fi

echo "# Extract .app from .tar.gz"
tar -xzf seedkeeper-pro-macos*.tar.gz

xattr -r -d com.apple.quarantine "Seedkeeper PRO.app"

if [ -n "$1" ] && [ -n "$2" ] # Standalone
then
	echo "#################"
	echo "# Two parameters have been given, this will be a standalone"
	echo "#################"
	echo
	echo "# Sign the main binaries, with the entitlements"
	codesign -f --timestamp --options runtime --entitlements helper.entitlements --sign 'Application' Seedkeeper\ PRO\ Manager.app/Contents/Resources/helper/authenticator-helper
else
	echo "#################"
	echo "# No parameters given, this will be app store"
	echo "#################"
	echo
	echo "# Sign the main binaries, with sandbox enabled, without hardened runtime"
	codesign -f --timestamp --entitlements helper-sandbox.entitlements --sign 'Application' Seedkeeper\ PRO\ Manager.app/Contents/Resources/helper/authenticator-helper
fi

echo "# Sign the dylib and so files, without entitlements"
cd Seedkeeper\ PRO\ Manager.app/
codesign -f --timestamp --options runtime --sign 'Application' $(find Contents/Resources/helper/_internal/ -name "*.dylib" -o -name "*.so")
cd ..

echo "# Sign the Python binary (if it exists), without entitlements"
codesign -f --timestamp --options runtime --sign 'Application' Seedkeeper\ PRO\ Manager.app/Contents/Resources/helper/_internal/Python

echo "# Sign the GUI"
codesign -f --timestamp --options runtime --sign 'Application' --entitlements Release.entitlements --deep "Seedkeeper PRO.app"

if [ -n "$1" ] && [ -n "$2" ] # Standalone
then
	echo "# Compress the .app to .zip and notarize"
	ditto -c -k --sequesterRsrc --keepParent "Seedkeeper PRO.app" "Seedkeeper PRO.zip" 
	STATUS=$(xcrun notarytool submit "Seedkeeper PRO.zip" --apple-id $1 --team-id LQA3CS5MM7 --password $2 --wait)
	echo ${STATUS}

	if [[ "$STATUS" == *"Accepted"* ]]; then
		echo "# Notarization successfull. Staple the .app"
		xcrun stapler staple -v "Seedkeeper PRO.app"

		echo "# Create dmg"
		rm seedkeeper-pro-macos.dmg # Remove old .dmg
		mkdir source_folder
		mv "Seedkeeper PRO.app" source_folder
		sh create-dmg.sh
		echo "# .dmg created."
	else
		echo "Error uploading for notarization"
		exit
	fi

	echo "# Sign the .dmg"
	codesign -f --timestamp --options runtime --sign 'Application' seedkeeper-pro-macos.dmg
	echo "# Notarize the .dmg"
	STATUS=$(xcrun notarytool submit "seedkeeper-pro-macos.dmg" --apple-id $1 --team-id LQA3CS5MM7 --password $2 --wait)
	echo ${STATUS}
	echo "# Staple the .dmg"
	xcrun stapler staple -v seedkeeper-pro-macos.dmg

	echo "# Everything should be ready for release!"
else # App store
	echo "# Build the package for AppStore submission"
	productbuild --sign 'Installer' --component "Seedkeeper PRO.app" /Applications/ output-appstore.pkg
fi

echo "# End of script"
