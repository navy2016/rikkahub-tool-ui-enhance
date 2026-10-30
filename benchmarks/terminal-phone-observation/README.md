# Optional read-only observation on the affected phone

Run only when the user chooses to connect and authorize ADB. Requires Python3 and an existingADB.
This is NOT executed by the assistant on the user's device. No install/uninstall/data clearing,
force-stop,compilation,permission or animation/refresh-rate changes. No screenshots,logcat or chat
text. Reads model/OS,the exact installedAPK hash,animation scales,batterytemperature and limited
compilation-status fields plus numericWindow frame records. DeviceADBserial is not saved.

```
python3 collect.py --phase settings --seconds 30
python3 collect.py --phase chat --seconds 30
python3 collect.py --phase terminal-ime --seconds 30
```

During each window,operate normally in that page. For terminal use DEFAULT in both versions and
open/closeIMEas the user described. The script never switches versions; do NOT uninstall/clear data
just to collect a reference. Collect reference only if already available through user's chosen safe
update procedure. EachJSONlabels the installedAPK by exactSHA256,never merelyversion2.1.62/151
(both builds share those values). Do not publish files without reviewing device metadata.

gfxinfo retains a bounded frame ring,so these are observed numericrecords,not allframes over30s.
Incomplete/flaggedrecords are retained. No reliableP99,noIMEspecific/input-to-presentclaim.
Read-onlyARTstatus is supplementary;an unsupportedquery must not be interpreted as uncompiled.
