# Operit preview.6 源码只读校验：只计算哈希、不修改任何文件。
# 用法：把 ROOT 改成你放源码/解压包的目录，然后原样运行。
import hashlib, os, time

ROOT = "/sdcard/Download/Operit"

EXPECTED = {
 "build-info.js": ["175fa5b5f0127d9daf8a8c31819741e1c8c065951f9ccb7ab4f757bbb4def2fd"],
 "checkin.js": ["2e52f8a7144843adfa55d66ff6ae7b39f2084865d7a6ae52097c0a76278ed2e2"],
 "cognitive_core.js": ["3aaf1290edf150cca53003f29a645fd031c3138969c4ea2d3a4dda88ecb2764b"],
 "companion.js": ["30ff430ac6adc64fb96092b8eb53d4ba78f738cff77c0a0f174a44b3933ff343"],
 "control-plane.js": ["cb7820dceb1af8b2f5e601121972890d354df8c328ec2a8ba08f0b27b7521813"],
 "control_plane.js": ["bccb38b4ab8422c42c3bd43ebdb2688f7c617a7988348c3861accd0063e9c15f"],
 "core.js": ["3aa69e00ca87935526757eef29dd9e667b9da8497fce33348ac2f683bb744fb3"],
 "device-observer.js": ["0408fb0385cd44b9ee7b427b4c2afc14801d46acd401da6cb10ddb47ec480313"],
 "executor-api.js": ["ebfb69d0ebdd1481c18a143dacdc180034dbd246f135466872ba6bd1a15c8d32"],
 "executor.js": ["e3c302ed5d5380a9268aa656f06fce2c60af11b25a51ead755ffc6ceacb7329e"],
 "external-locks.js": ["46ed31743815f3c1683e802e694b025223db46578ab411d62927c384bc3daf5a"],
 "file-reader.js": ["31394e896afdb63683891ebc1cf70da4dfeff688f95811d3f3bdeac7745d8574"],
 "finite-lease.js": ["99ffca1dfbdb113929298775d3e8696d463b133ddf26e6202fd91a427e7a2fff"],
 "focus-api.js": ["317059af0b74f6d97b813eb778857253b023c2ff0f2999a798507ef07bafe0f8"],
 "focus-scheduler.js": ["1f5430a78a0e25433127979b3112a5340b66b3168848309b961f2c4abe484b23"],
 "focus.js": ["8349f5e8c17e9067a211d9d7a3ee2db2c3f5fbb84caed7bb6d5d72602331fe49"],
 "focus_hub_data.js": ["8af8c1b50806de2ee165e459ac3cd8a59af440b863afa3597d3224ef07a45f66"],
 "focus_hub_nav.js": ["e283e2ea73bd3174dbfd1782c8da28908ae5f22c58f132ed8afcd881f37bbc25"],
 "focus_hub_progress.js": ["b83b8228568cd8eb85cfd53cb90fc3fca349852a9f74b6cf310be000817912dd"],
 "focus_hub_slim.js": ["59bc505a188da912923b27691e2f6ca51d64fbf016d540ff1ea76e940741fc5e"],
 "focus_hub_warden.js": ["c14131199294600c63e3a9155c6cf61178a8d6ea4af1e82bd42013b3a6ed484d"],
 "format.js": ["eed3c9c55fd1f33b7e404f40bd8044f2f805e40c250e73219c6b3f322d87724d"],
 "gate-api.js": ["750f1bbae5482a5193e0fb424ad305d3daacdd99fbe1f9d0ae3bf05bc78a964a"],
 "gate-spec.js": ["e615d22d6e52ce19ee8d15f6f90b162f3363f8dc04427e35e0f0ee63fb15f468"],
 "gate.js": ["59e61c50e48e71aeb85c9da06f7a74a216fed31f7e367479ee2424d8022f63c4"],
 "health.js": ["467b309719a0fae699a083c3047d13c216372fd17f1804ed82317b03f4ae55b8"],
 "index.ui.js": ["b16445b72f80457434053b40245bb8e056f0c5d591cb316f3e415837682930b3"],
 "keys.js": ["dd98ad543e5e3468923ce6b003c63e924cfc23a43dbb4684465ef6514fe2fad8"],
 "live-api.js": ["059f56060512dcc1c618c1cdfabdf6ee2f0eb8fc017be0901fc3d3cdcc3cd7af"],
 "live-control.js": ["51b2e1854f2145313399f77bc744422c43c0aa48d71e0267b8fa4e3846dc5c47"],
 "live-scheduler.js": ["1c15aef20b37b0f51370ca21eaaa0ed2eaa93c5e1d737a5bf4a913c26df12f0f"],
 "main.js": ["362c6bab48d2bfc8d1f4f5debcc7532e4511f914ae2185b5f8a15ec69d47f513", "6ab5af42de12381819699c34fd75b3d0b7b703a51159f2fb52a1c291279b6386"],
 "model-output.js": ["38f63665b5666cdc373d1e1bb457b1cc93cc37cddc6f9a4387b3a8ed6f79dba3"],
 "mood.js": ["e4aec5befccad25cc953054f49204a720d0582861bc102757b2d2bba0c6226bd"],
 "nav.js": ["c211036ccc1797d7904c8bff945cf7bedfe6ce70d33adb52a107d45bd4b4aa5d"],
 "operit-provider.js": ["ae4b2f98ce8c3ddd459c350a5a5196dbc42b48216358ec8e80fbc7ad67cc1eeb"],
 "panel.js": ["84c20a482f5ea149fbd38cf678e6c68534215b1aff77accf049ecfdb3b442c25"],
 "participation.js": ["79ea4b721237181f13e6fe1f4c62b53847fff3cd588142e5ce89c8c82321ea29"],
 "paths.js": ["c21d9fab76cf92cb1c498824c43eb70c78b74cce0b3e28f1f46c3dd0c93d4d16"],
 "pause-records.js": ["2e2a4fe36f6cf1fd9d7e399f65bc3530b8b996153da2cff366b1bab826d86376"],
 "phone-api.js": ["956552088ed28fe5603965198b02cc2f236111adca767541a46a84b90731028d"],
 "phone-store.js": ["74e27a16aaadb25534c51f236504adfd83322ee3ef56454a8ba65858784c8760"],
 "progress.js": ["b451420b17312613e214db52702c83026482c8d5b96e24ea328f2f50af8c1ce0"],
 "reading-api.js": ["dd6b384dddbdce335ebe33c6e32fa3eb3287d3ac670617e5cdc2dcb61a676f01"],
 "reading.js": ["e92147c0f056a45d66f5817faf6c82a64068104cd56e09c6ca969311c3dd6eec"],
 "runtime.js": ["225d017da66d5f0adbcdbcccde62654f032816e5785b7611d0c130d6abbf017a"],
 "shell-provider.js": ["1129de040ab30ffdcd4a73e4d483b458475c939b2942ea891949d5b7a082bbd6"],
 "slim.js": ["de17ffe348386e74db62c91341ae91f35da3be3bf04b50ed41139b4154dc2295"],
 "snapshot.js": ["92f3684072f76b34763306e89cb37beeba1d4b06dd52a70002737eb0f8d27426"],
 "speech.js": ["0a8e9b4d3afa477bf32c409396a3bae7b6510f8f42eb46a59cd28b4da2aa4eda"],
 "strict-api.js": ["b8802846781676e55ca9cc01bb25deb1f8dca9e03a35760829e71d18d5df066b"],
 "strict.js": ["0ee77f1732f5d36fefdd7eb65c357d5b659c5770c7616f03eaef0e9258824169"],
 "task-entry.js": ["73d135287464fe6ef7a5efb94c035f4b92ddca95bb613e2c202a32e4fe3ec946"],
 "text-tail.js": ["92424f635521b6e3e7181e4ce2115c2647088110e2d9327ba94db63c0904519b"],
 "warden.js": ["3d2d9e605ddc94b3cd3fcfd505e5dd7f641892ed530a91a54e26c9c969f832f4"],
 "work-environment.js": ["68367928be6c9971200cbc5ddddda3e7ab118e2e1d3e225147fa5ca58a5bc355"],
 "work-loop-api.js": ["03bc3067cf64e9245d98c99a4d27901dee93cbe3b74345ef6453942e578d20ff"],
 "work-loop-bridge.js": ["a636228e2090508b0c4f8a57591f3f538fe15bd54a3502a3843a3826b12db724"],
 "work-loop.js": ["8119a5c1584c174d38d67069b5b09b878a5eb5b4b7d05dd2a2169772f7243ba3"],
}

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

ok = changed = 0
for d, _dirs, files in os.walk(ROOT):
    for name in files:
        if name not in EXPECTED:
            continue
        p = os.path.join(d, name)
        try:
            same = sha(p) in EXPECTED[name]
        except OSError as e:
            print("READ_FAIL", p, e)
            continue
        if same:
            ok += 1
        else:
            changed += 1
            print("CHANGED", p, "modified", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(p))))
print("MATCH", ok, "CHANGED", changed)
