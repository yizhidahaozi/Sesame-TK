#!/bin/bash

# 示例1：更新版本号
if [ -f "app/build.gradle" ]; then
  CURRENT=$(grep -E 'versionName ".*"' app/build.gradle | awk -F'"' '{print $2}')
  NEW_VERSION="${CURRENT}.${RANDOM:0:3}"
  sed -i "s/versionName \"${CURRENT}\"/versionName \"${NEW_VERSION}\"/" app/build.gradle
  echo "版本更新: ${CURRENT} → ${NEW_VERSION}"
fi

# 示例2：更新README时间戳
if [ -f "README.md" ]; then
  TIMESTAMP="最后更新: $(date '+%Y-%m-%d %H:%M')"
  sed -i "s/<!-- LAST_SYNC -->.*/<!-- LAST_SYNC --> ${TIMESTAMP}/" README.md
fi
