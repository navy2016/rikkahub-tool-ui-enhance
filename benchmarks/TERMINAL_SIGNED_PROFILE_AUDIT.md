# Exact signed APK profile audit, not phone ART/AOT state

User confirms a slight212b93e→55db111 slowdown acrosschat/settings/allUI and both-default terminalIME.
Read only the original APKs identified bySHA2567c5c3904ec355eb190b88ebbc4a2f2b260a76748661094658428b4f40a6700a1
and5fdaceacfbfaff5d378f9b2ac23070f009c6bf347be42d1561fb53e6080c6f4c. No rebuild/install/localAPK.
Parse bundledprof010/profm002,verify count/indexbounds andDEX identity. Profileschanging is normal
aftercodechanges;matching shipped data doesNOTprove userdevice installed/compiled/used that profile.

Initialauditb5039b3/run41075099086rejected atchecksum because harness compared profilelocationchecksum
toDEXheaderAdler32. ART DexFileLoader::GetMultiDexChecksums usesZIPentryCRC forAPKcontainers and
headerchecksum forrawDEXfiles. Corrected parser usesZIPCRCwhileindependentlycheckingDEXAdler32/SHA1.
Preserve initial audit as a parser-definition failure,not evidence of a corrupt APK. Rawprofile files
and parsed diagnostics are saved beforevalidation to make future rejection independently inspectable.
Reference:https://android.googlesource.com/platform/art/+/refs/heads/main/libdexfile/dex/dex_file_loader.cc

Correctedaudit7743a18/run41075540623passed.BothoriginalAPKs profile010/metadata002 matchZIPCRC,
DEXinternalAdler32/SHA1,method/type/classbounds. Referenceprofiledmethods10110+1462;candidate10094+1487.
Counts changes alone do not show missing critical appcoverage. FulloriginalreportSHA256
b5362a330fe9405156b1be2e281d8bed5943ad3f3ed309cc17b708e13db2ad32,121324B.
APIstepkept10of11annotationfragments;thecompleteartifact12985729478/relay41076100412wasretrieved,
notrerun. Rawprof/profm retained;noAPKdownloaded locally or installed onuserphone.
