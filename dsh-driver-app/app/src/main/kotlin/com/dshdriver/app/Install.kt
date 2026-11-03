package com.dshdriver.app

/** 发给大肥鱼（DSH Go 里的 agent）的安装说明：它照着在自己的本机环境里装插件、生成配对码。 */
object Install {
    const val BRANCH = "ccr-8329c7b5-gr96ko"
    const val PLUGIN_DIR = "/root/.dsh/dsh-go/plugins/dsh-driver-core"

    val TEXT = """
请帮我安装「思维驾驶员」APP 的配套插件 dsh-driver-core。在你自己的本机环境终端里依次执行，每一步把输出给我看：

1. 下载插件到 $PLUGIN_DIR（已有旧版会被替换）：
mkdir -p /root/.dsh/dsh-go/plugins && cd /root/.dsh/dsh-go/plugins && rm -rf dsh-driver-core.tmp && mkdir dsh-driver-core.tmp && curl -fsSL https://codeload.github.com/aozhenyu666-cmyk/test/tar.gz/refs/heads/$BRANCH | tar -xz -C dsh-driver-core.tmp --strip-components=3 test-$BRANCH/dsh-driver-core/plugin && rm -rf dsh-driver-core && mv dsh-driver-core.tmp dsh-driver-core && ls dsh-driver-core

2. 安装（装上即生效，不用重启）：
dsh plugin --profile web add $PLUGIN_DIR

3. 生成配对码，把输出里"配对码："后面那一串原样告诉我：
node $PLUGIN_DIR/bin/pair-code.mjs

注意：不要修改插件代码，也不要换别的装法；任何一步出错，把完整报错发给我。如果是更新已装过的插件：第 2 步提示已经装过没关系，但要在「设置 › 插件」里点「重启内核以重载」才会加载新代码。
""".trim()
}
