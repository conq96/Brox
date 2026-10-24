#!/bin/bash

GRADLE_FILE="android/app/build.gradle"

if [ ! -f "$GRADLE_FILE" ]; then
    echo "Hata: $GRADLE_FILE bulunamadı! Lütfen projenin ana dizininde olduğundan emin ol."
    exit 1
fi

# Mevcut versionCode'u bul ve 1 artır
V_CODE=$(grep 'versionCode' $GRADLE_FILE | awk '{print $2}')
NEW_V_CODE=$((V_CODE + 1))
sed -i "s/versionCode $V_CODE/versionCode $NEW_V_CODE/" $GRADLE_FILE

# versionName'i güncelle (Örn: "1.0" -> "1.0.X")
sed -i "s/versionName \".*\"/versionName \"1.0.$NEW_V_CODE\"/" $GRADLE_FILE

echo "✅ Brox Versiyonu Güncellendi: v1.0.$NEW_V_CODE (Build: $NEW_V_CODE)"

# Git işlemlerini yap
git add .

if [ -z "$1" ]; then
    git commit -m "Uygulama versiyonu v1.0.$NEW_V_CODE sürümüne güncellendi"
else
    git commit -m "$1"
fi

git push -u origin main --force
echo "🚀 Güncellemeler GitHub'a başarıyla gönderildi ve derleme başladı!"
