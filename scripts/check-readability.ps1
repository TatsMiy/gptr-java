<#
.SYNOPSIS
  可读性判据门禁 —— 一条命令检查全仓 main 源码，有违规即非 0 退出。

.DESCRIPTION
  判据出处：rules/coding-discipline.md §B8（阈值必须带出处）
            与 design/done/design-code-readability-refactor-20260915.md §5。

  本脚本是这些判据的**唯一可执行来源**，不叠加 Checkstyle/PMD —— 因为本项目
  两条主因（lambda 体长、异常信息是否丢失）**无现成工具可查**，若只接现成工具
  等于"守住了格式、放掉了主因"。

  ⚠️ 已知局限（如实声明，不要当它是完整静态分析）：
    1. 不解析 AST，全部基于「文本 + 花括号配对」；字符串、字符字面量与注释先剥离。
    2. 嵌套三元用**保守近似**：同一逻辑语句内出现 >=2 个三元 '?' 即判为嵌套。
       会漏掉更隐蔽的形态（宁可漏报，不可误报 —— 门禁的价值在于不制造噪音）。
    3. catch 判据检查的是「**异常信息是否丢失**」，而非机械的「块内无日志」：
       记日志 / 抛出 / Thread.currentThread().interrupt() / 项目遥测
       （notePayload、activity、sink.emit）/ 注释说明 / 写进返回值或字段 —— 任一即视为已响应。
       依据 Google Java Style §6.2（"completely no action" 才需注释说明理由）。
    4. 参数个数（规则 9）/ record 分量数（规则 10）按**顶层逗号**近似计数，不建 AST：
       注解中带 '(' 的声明、缩进非 4 空格的声明（如顶层 record）等个别形态会漏报。
       两个规则是**两个不同的检查**（ParameterNumber / RecordComponentNumber），不合并计数。

.PARAMETER Root
  仓库根目录。默认取脚本所在目录的上一级。

.PARAMETER ShowAll
  打印全部违规，而非每类仅前 10 条。

.EXAMPLE
  pwsh -File scripts/check-readability.ps1
#>
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$ShowAll
)

$ErrorActionPreference = 'Stop'
# 输出编码显式设为 UTF-8：Windows PowerShell 5.1 默认按系统 ANSI(GBK) 输出到管道，
# 而调用方（ReadabilityGateTest 用 UTF-8 解码）会拿到乱码 —— 实测违规清单变成 "??"。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ── 阈值：每一项都必须能追溯到公开规范或工具默认值（§B8 规则 1）──────────────
$Limits = [ordered]@{
    MaxLineLength      = 120  # 阿里《Java 开发手册（泰山版）》·代码格式 8【强制】
    MaxMethodLines     = 80   # 阿里·代码格式 11【推荐】（PMD 默认 100 / Checkstyle NCSS 50，取最严）
    MaxBlockLambdaBody = 3    # Effective Java 3rd, Item 42（1 行理想，3 行上限；超限应「消除」）
    MaxInlineFqn       = 0    # 阿里·OOP 1【强制】/ Google §6.3
    MaxSilentCatch     = 0    # Google Java Style §6.2【强制】：catch 内**既无实质动作也无注释**
    MaxCommentOnlyCatch = 41  # 棘轮（2026-09-17 新增）：catch 内**仅有注释**；初测 42，同日修 SearchNode#safeSearch 后 41
    MaxNestedTernary   = 0    # SonarQube S3358
    MaxUnusedImport    = 0    # 阿里·注释规约 8【推荐】
    MaxControlNesting  = 3    # 阿里·控制语句 7【推荐】：控制语句嵌套 ≤3 层，超出用卫语句
    MaxParams          = 7    # Checkstyle ParameterNumber（官方默认 max=7）：方法/构造器参数个数
    # 参数超限的**存量棘轮**：只允许下调（§B8 规则 7「基线清零后接入」的过渡形态）。
    # 口径见下方「规则 9」。2026-09-16 接入时实测 7 处（engine 6 + benchmark 1）；
    # A9 批 1-4 逐处收窄后**归零**（2026-09-17 实测违反数 0）——此后任何新增超标签名立即 [WARN]。
    #
    # 说明：先前的「缩进 >=5 层」指标已**删除**——它的出处（阿里·控制语句 7）讲的是
    # 「控制流嵌套」，而它实际数的是「任何缩进 >=20 空格的行」，**含参数续行与链式续行**；
    # 而阿里手册对换行的规定恰恰是「第二行相对第一行缩进 4 个空格」——即该指标会惩罚
    # **符合规范**的换行。参数多才是根因，改由规则 9 直接度量参数个数。
    MaxParamViolations = 0
    MaxRecordComponents  = 8  # Checkstyle RecordComponentNumber（官方默认 max=8）：record 头部分量个数
    # record 分量的**存量棘轮**同上：2026-09-16 接入时实测 5 处；A9 批 5 把 ResearchOptions
    # 由 16 分量拆为 6 分量后实测 4 处（均为 benchmark 侧书面接受项：
    # KeypointCoverage.Result 15 / MicroGold.Report 11 / ItemRunContext 10 / Verdict 9）。
    # 与规则 9 是**两个不同的检查**：ParameterNumber 的 token 只有 METHOD_DEF/CTOR_DEF，
    # 而 record 的规范构造器是**隐式的**、不产生 CTOR_DEF 节点，故 record 头另由本项度量。
    MaxRecordViolations  = 4
    # 方法超长的**存量棘轮**同上：2026-09-16 修漏报后首次测得真实存量 7 处
    # （含 3 个 main：297 / 162 / 133 行——旧实现因 throws 子句与多行签名从未检查过它们）。
    MaxMethodViolations  = 7
}

# ── 扫描范围：仅 **main** 源码 ───────────────────────────────────────────────
# 测试代码（src/test/java）**不在范围内**：实测有 53 处违规（块 lambda >3 行 9 /
# 单行 >120 共 12 / 内联全限定名 24 / 未使用 import 8），需先用一批清理完才能纳入。
# 已登记 todo/BACKLOG.md。在那之前，本门禁只保证**生产代码**不腐化。
$SourceDirs = @(
    (Join-Path $Root 'gptr-engine\src\main\java'),
    (Join-Path $Root 'gptr-benchmark\src\main\java')
)

$fileList = @()
foreach ($dir in $SourceDirs) {
    if (Test-Path $dir) { $fileList += Get-ChildItem -Recurse -Filter '*.java' $dir }
}
if ($fileList.Count -eq 0) { throw "未找到源码：$($SourceDirs -join ' ; ')" }

# 匹配「以大写字母开头的类名」结尾的多段限定名（内联全限定名）
$fqnPattern = [regex]'(?:java|com\.gptr)(?:\.[a-z][\w]*)*\.[A-Z][\w]*(?:\.[A-Z][\w]*)*'
# catch 判据分两级（2026-09-17 起）：
#   $realAction  = **实质动作**：记日志 / 抛出 / 中断 / 写状态 / 进返回值 —— 命中即「异常信息未丢失」
#   $commentOnly = **仅注释**：**不算**实质动作。历史口径把 `//` 也算合规证明，于是
#                  「写一行注释」即可静默吞掉任意异常（实例：`SearchNode#safeSearch` 吞检索异常）。
# ⚠️ 两条判据**共用** $realAction，禁止各自再写一份正则（§B1「同一份数据不得两处声明」）。
$realAction = 'LOG\.|log\.|throw |AssertionError|interrupt\(\)|notePayload\(|activity\(' +
              '|\.emit\(|sink\.|System\.(out|err)\.|\.put\(|last\s*=' +
              '|lastFailure\s*=|new ResearchOutcome\(|getMessage\(\)|toString\(\)'
$commentOnly = '//|/\*'

$violations = [ordered]@{
    LineLength     = New-Object System.Collections.Generic.List[string]
    MethodLength   = New-Object System.Collections.Generic.List[string]
    BlockLambda    = New-Object System.Collections.Generic.List[string]
    NestedLambda   = New-Object System.Collections.Generic.List[string]
    InlineFqn      = New-Object System.Collections.Generic.List[string]
    SilentCatch    = New-Object System.Collections.Generic.List[string]
    CommentOnlyCatch = New-Object System.Collections.Generic.List[string]
    NestedTernary  = New-Object System.Collections.Generic.List[string]
    UnusedImport   = New-Object System.Collections.Generic.List[string]
    ControlNesting = New-Object System.Collections.Generic.List[string]
    ParamCount     = New-Object System.Collections.Generic.List[string]
    RecordComponents = New-Object System.Collections.Generic.List[string]
    PrivateRef     = New-Object System.Collections.Generic.List[string]
}

# ── 共用度量：从 $Lines[$Start] 的**首个 '('** 配对到配平的 ')'，返回顶层参数个数 ──
# 规则 9（方法/构造器参数）与规则 10（record 头分量）共用本函数——两处口径必须一致，
# 否则「参数个数」与「分量个数」会因计数方式不同而无法互相解释。
# 口径：只数**顶层逗号**（跳过 <> 泛型与嵌套括号）；声明跨行时继续读下一行。
# 返回 $null = 该行没有 '('（调用方须区分「0 个参数」与「没找到」）。
function Measure-TopLevelArgs {
    param([string[]]$Lines, [int]$Start)
    $depth = 0
    $angle = 0
    $commas = 0
    $sawContent = $false
    $started = $false
    for ($j = $Start; $j -lt $Lines.Count; $j++) {
        foreach ($ch in $Lines[$j].ToCharArray()) {
            if (-not $started -and $ch -ne '(') { continue }
            if ($ch -eq '<') { $angle++ }
            elseif ($ch -eq '>') { if ($angle -gt 0) { $angle-- } }
            elseif ($ch -eq '(') { $depth++; $started = $true }
            elseif ($ch -eq ')') {
                $depth--
                if ($depth -eq 0) { break }
            } elseif ($depth -eq 1 -and $angle -eq 0 -and $ch -eq ',') { $commas++ }
            elseif ($depth -eq 1 -and $angle -eq 0 -and -not [char]::IsWhiteSpace($ch)) { $sawContent = $true }
        }
        if ($started -and $depth -eq 0) { break }
    }
    if (-not $started) { return $null }
    return $(if ($sawContent) { $commas + 1 } else { 0 })
}
$totalLines = 0

foreach ($file in $fileList) {
    $lines = [System.IO.File]::ReadAllLines($file.FullName)
    $totalLines += $lines.Count
    $rel = $file.FullName.Substring($Root.Length).TrimStart('\')
    $classBase = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)

    # ---- 1) 单行长度（阿里·代码格式 8【强制】）----
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].Length -gt $Limits.MaxLineLength) {
            $violations.LineLength.Add("$rel`:$($i + 1)  len=$($lines[$i].Length)")
        }
    }

    # ---- 2) 内联全限定名（阿里·OOP 1 / Google §6.3）----
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $trimmed = $lines[$i].Trim()
        if ($trimmed.StartsWith('import ') -or $trimmed.StartsWith('*')) { continue }
        foreach ($m in $fqnPattern.Matches($lines[$i])) {
            $violations.InlineFqn.Add("$rel`:$($i + 1)  $($m.Value)")
        }
    }

    # ---- 3a) 控制语句嵌套（阿里·控制语句 7【推荐】：≤3 层，超出用卫语句）----
    # 口径：花括号栈统计 if/else/for/while/do/switch/try/catch 块的嵌套深度。
    # **续行不入栈** —— 参数换行/方法链换行不含控制流关键字，故不计入（这就是与 3b 的分界）。
    # 保守近似：同一行出现多个 '{' 时，全部沿用该行的控制流标记（宁可多算，不可漏算）。
    $ctrlStack = New-Object System.Collections.Generic.List[bool]
    $fileMaxCtrlNesting = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $clean = $lines[$i] -replace '//.*$', ''
        $clean = $clean -replace '"(?:\\.|[^"\\])*"', '""'
        $clean = $clean -replace "'(?:\\.|[^'\\])*'", "''"
        if ($clean.Trim() -eq '') { continue }
        $braceIdx = $clean.IndexOf('{')
        $head = if ($braceIdx -ge 0) { $clean.Substring(0, $braceIdx) } else { '' }
        $isCtrl = $head -match '(^|[;{}]\s*)(if|else|for|while|do|switch|try|catch)\b'
        for ($c = 0; $c -lt $clean.Length; $c++) {
            $ch = $clean[$c]
            if ($ch -eq '{') {
                $ctrlStack.Add([bool]$isCtrl)
                if ($isCtrl) {
                    $d = 0
                    foreach ($b in $ctrlStack) { if ($b) { $d++ } }
                    if ($d -gt $fileMaxCtrlNesting) { $fileMaxCtrlNesting = $d }
                }
            } elseif ($ch -eq '}' -and $ctrlStack.Count -gt 0) {
                $ctrlStack.RemoveAt($ctrlStack.Count - 1)
            }
        }
    }
    if ($fileMaxCtrlNesting -gt $Limits.MaxControlNesting) {
        $violations.ControlNesting.Add("$rel  max=$fileMaxCtrlNesting（上限 $($Limits.MaxControlNesting)）")
    }

    # ---- 9) 参数个数（Checkstyle ParameterNumber；官方默认 max=7，tokens = METHOD_DEF/CTOR_DEF）----
    # 口径：类成员层（缩进 4 空格）的方法/构造器声明，自首个 '(' 配对到 ')'，
    # 只数**顶层逗号**（跳过 <> 泛型与嵌套括号）；声明跨行时继续读下一行。
    # record 声明**不计入**：其规范构造器是隐式的，不产生 CTOR_DEF 节点（严格对齐出处）
    # —— record 头部的分量个数归规则 10（RecordComponentNumber，官方默认 8）。
    # 文本近似：多行签名可能漏报 —— 宁可漏报，不可误报（同规则 8 的取舍）。
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $decl = $lines[$i]
        if ($decl -notmatch '^    (?:@\w+\s+)*(?:public |private |protected |static |final |abstract |synchronized |default )*[\w<>\[\],\.]+(?:\s+[\w<>\[\],\.]+)*\s*\(') {
            continue
        }
        if ($decl -match '\brecord\s+\w') { continue }   # record 声明不计（见上方口径），归规则 10
        $params = Measure-TopLevelArgs -Lines $lines -Start $i
        if ($null -ne $params -and $params -gt $Limits.MaxParams) {
            $violations.ParamCount.Add("$rel`:$($i + 1)  params=$params")
        }
    }

    # ---- 10) record 分量数（Checkstyle RecordComponentNumber；官方默认 max=8）----
    # 为何与规则 9 分开：这是**两个不同的检查**，处置方式也不同——参数过多拆**签名**
    # （提取参数对象），record 头过宽拆**字段组**（按用途分成多个 record）。
    # 口径同 Measure-TopLevelArgs；文本近似：只认类成员层（缩进 4 空格）的 record 声明。
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $decl = $lines[$i]
        if ($decl -notmatch '^    (?:@\w+\s+)*(?:public |private |protected |static |final )*record\s+\w') {
            continue
        }
        $components = Measure-TopLevelArgs -Lines $lines -Start $i
        if ($null -ne $components -and $components -gt $Limits.MaxRecordComponents) {
            $violations.RecordComponents.Add("$rel`:$($i + 1)  components=$components")
        }
    }

    # ---- 4) 未使用 import（阿里·注释规约 8）----
    $fileText = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($line in $lines) {
        if ($line -notmatch '^import\s+(?:static\s+)?([\w\.]+);') { continue }
        $simple = (($matches[1]) -split '\.')[-1]
        if (([regex]::Matches($fileText, '\b' + [regex]::Escape($simple) + '\b')).Count -le 1) {
            $violations.UnusedImport.Add("$rel  $($line.Trim())")
        }
    }

    # ---- 5) 块 lambda：体长 >3 / 内部再嵌 lambda（Effective Java Item 42）----
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch '->\s*\{\s*$') { continue }
        $depth = 1
        $j = $i + 1
        while ($j -lt $lines.Count -and $depth -gt 0) {
            $depth += ([regex]::Matches($lines[$j], '\{')).Count
            $depth -= ([regex]::Matches($lines[$j], '\}')).Count
            $j++
        }
        $bodyLines = $j - $i
        if ($bodyLines -gt $Limits.MaxBlockLambdaBody) {
            $violations.BlockLambda.Add("$rel`:$($i + 1)  body=$bodyLines 行")
        }
        $inner = ([regex]::Matches(($lines[$i..([Math]::Min($j - 1, $lines.Count - 1))] -join ' '), '->')).Count
        if ($inner -gt 1) {
            $violations.NestedLambda.Add("$rel`:$($i + 1)  含 $($inner - 1) 个内层 lambda")
        }
    }

    # ---- 6) 方法体长度（阿里·代码格式 11【推荐】；排除构造器）----
    # 口径：4 空格缩进 + 返回类型 + 方法名 + '('，签名**可跨行**、**可带 throws 子句**。
    # 2026-09-16 修两处漏报（旧实现要求声明行同时含 '(' ')' '{'）：
    #   ① 参数列表跨行的方法（如返回类型为 Map<String, Object> 时换行）从未被检查；
    #   ② 带 throws 子句的方法从未被检查——**含三个 main（297 / 162 / 133 行）**。
    # 实测修前只看见 1 处、真实存量 7 处；修后本判据降为**棘轮**（存量 7 处作基线）。
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch '^    [\w<>\[\],\. ]+\s+(\w+)\s*\(') { continue }
        $methodName = $matches[1]
        if ($methodName -eq $classBase) { continue }
        # 先找到签名结束的 '{'：跳过参数列表的括号；遇顶层 ';' 视为无体声明（接口/抽象方法）
        $k = $i
        $paren = 0
        $brace = $false
        $semi = $false
        while ($k -lt $lines.Count) {
            foreach ($ch in $lines[$k].ToCharArray()) {
                if ($ch -eq '(') { $paren++ }
                elseif ($ch -eq ')') { if ($paren -gt 0) { $paren-- } }
                elseif ($ch -eq '{' -and $paren -le 0) { $brace = $true; break }
                elseif ($ch -eq ';' -and $paren -le 0) { $semi = $true; break }
            }
            if ($brace -or $semi) { break }
            $k++
        }
        if (-not $brace) { continue }
        $depth = 1
        $j = $k + 1
        while ($j -lt $lines.Count -and $depth -gt 0) {
            $depth += ([regex]::Matches($lines[$j], '\{')).Count
            $depth -= ([regex]::Matches($lines[$j], '\}')).Count
            $j++
        }
        if (($j - $i) -gt $Limits.MaxMethodLines) {
            $violations.MethodLength.Add("$rel`:$($i + 1)  $methodName  ($($j - $i) 行)")
        }
    }

    # ---- 7) catch：异常信息是否丢失（Google §6.2【强制】）----
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -notmatch 'catch\s*\(') { continue }
        # 块体 = catch 行 '{' 之后的**行内部分** + 后续行（到括号配平）。
        # ⚠️ 必须含行内部分：单行 catch（`catch (X e) { return y; }`）块体全在该行，
        #    若只从下一行取，会取到无关代码 ⇒ 既漏报又可能误判（2026-09-17 修）。
        $tail = if ($lines[$i] -match 'catch\s*\([^)]*\)\s*\{(.*)$') { $Matches[1] } else { '' }
        # 行内是否已配平（单行 catch）：用它判断要不要读后续行。
        # ⚠️ 不能用「循环后的 $depth」判断——它退出时必然 ≤ 0（2026-09-17 曾因此误报 94 处）。
        $body = $tail
        $inlineDepth = 1 + ([regex]::Matches($tail, '\{')).Count - ([regex]::Matches($tail, '\}')).Count
        if ($inlineDepth -gt 0) {
            $depth = $inlineDepth
            $j = $i + 1
            while ($depth -gt 0 -and $j -lt $lines.Count) {
                $depth += ([regex]::Matches($lines[$j], '\{')).Count
                $depth -= ([regex]::Matches($lines[$j], '\}')).Count
                if ($depth -le 0) { break }
                $j++
            }
            $body = $tail + "`n" + ($lines[($i + 1)..([Math]::Min($j, $lines.Count - 1))] -join "`n")
        }
        if ($body -notmatch $realAction) {
            if ($body -match $commentOnly) {
                # 明细带「异常类型 + 块体首句」⇒ 不打开文件也能判断该不该修
                $excType = if ($lines[$i] -match 'catch\s*\(\s*([\w\.]+)') { $Matches[1] } else { '?' }
                $first = ''
                foreach ($bl in ($body -split "`n")) {
                    $t = $bl.Trim()
                    if ($t -and -not $t.StartsWith('//') -and -not $t.StartsWith('*') -and -not $t.StartsWith('/*') -and $t -ne '}') {
                        $first = $t
                        break
                    }
                }
                if (-not $first) { $first = '(仅注释，块体内无语句)' }
                if ($first.Length -gt 70) { $first = $first.Substring(0, 70) + '...' }
                $violations.CommentOnlyCatch.Add("$rel`:$($i + 1)  catch($excType) -> $first")
            } else {
                $violations.SilentCatch.Add("$rel`:$($i + 1)")
            }
        }
    }

    # ---- 8) 嵌套三元（SonarQube S3358）----
    # ⚠️ 文本方案**无法**可靠判定嵌套三元（本质需要 AST）。故只守两个**低误报**形态：
    #   8a) cond ? a : ( cond2 ? b : c )   ← false 分支带括号且括号内含三元
    #   8b) 未闭合 '?' 计数达 2 层          ← true 分支（含实参、可跨行）内含三元
    #
    # 8b 口径：按**语句**（以 ';' 结尾聚合，可跨行）扫描；'?' 使未闭合计数 +1，
    # ':' 使其 −1（仅在 >0 时）。峰值 ≥2 即判嵌套。这能区分「嵌套」与「并列」：
    #     foo(x ? 1 : 2, y ? 3 : 4)                     → 峰值 1，不报（并列）
    #     withScrape ? f(a == null ? null : b) : null   → 峰值 2，报（true 分支内嵌）
    # 已排除方法引用 '::'。仍在盲区：false 分支**不带括号**的嵌套 —— 宁可漏报，不可误报。
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $trimmed = $lines[$i].Trim()
        if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//')) { continue }
        $clean = $lines[$i] -replace '//.*$', ''
        $clean = $clean -replace '"(?:\\.|[^"\\])*"', '""'
        $clean = $clean -replace "'(?:\\.|[^'\\])*'", "''"
        if ($clean -match ':\s*\([^()]*\?') {
            $violations.NestedTernary.Add("$rel`:$($i + 1)  $trimmed")
        }
    }

    # ---- 8b) 嵌套三元：true 分支（含实参、可跨行）内含三元 ----
    # 三类噪音必须排除，否则全是误报（实测首版 5 报 5 误）：
    #   ① 文本块 """ 里的 SQL '?' 占位符       → $inTextBlock 整体跳过
    #   ② 泛型通配符 <?, ?> / <? extends X>    → 剥掉整个 <...>
    #   ③ 注释行里的记号（javadoc 的 (?U) 等） → 跳过 '*' / '/*' / '//' 开头的行
    $stmt = ''
    $inTextBlock = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $raw8 = $lines[$i]
        $trimmed8 = $raw8.Trim()
        $quotes = ([regex]::Matches($raw8, '"""')).Count
        if ($inTextBlock) {
            if ($quotes -ge 1) { $inTextBlock = $false }
            continue
        }
        if ($quotes -ge 1) {
            if ($quotes % 2 -eq 1) { $inTextBlock = $true }
            continue
        }
        if ($trimmed8.StartsWith('*') -or $trimmed8.StartsWith('/*') -or $trimmed8.StartsWith('//')) {
            continue
        }
        $clean8 = $raw8 -replace '//.*$', ''
        $clean8 = $clean8 -replace '"(?:\\.|[^"\\])*"', '""'
        $clean8 = $clean8 -replace "'(?:\\.|[^'\\])*'", "''"
        $clean8 = $clean8 -replace '<\?[^<>]*>', '<>'
        $clean8 = $clean8 -replace '::', ''
        $stmt += ' ' + $clean8
        if ($clean8 -notmatch ';\s*$' -and $i -lt $lines.Count - 1) { continue }
        $d = 0
        $maxD = 0
        foreach ($ch in $stmt.ToCharArray()) {
            if ($ch -eq '?') {
                $d++
                if ($d -gt $maxD) { $maxD = $d }
            } elseif ($ch -eq ':' -and $d -gt 0) {
                $d--
            }
        }
        if ($maxD -ge 2) {
            $violations.NestedTernary.Add("$rel`:$($i + 1)  未闭合 ? 达 $maxD 层")
        }
        $stmt = ''
    }
}

# ── 规则 11：私有引用（公开物不得引用私有仓文档；§B2.5）────────────────────────
# ⚠️ 与其他规则的根本差异：**不剥离注释与字符串** —— 引用恰恰就在注释与注解值里。
# 因此它独立收集文件集（git 跟踪的全部文本文件），不复用上面的 $fileList。
# 范围依据：发布树 = `git archive HEAD`，故 `git ls-files` 就是"会公开的文件"。
# 排除三项：导出时被整文件替换的两个本机文件、本脚本自身、二进制与外部数据集。
# 模式集用 Contains() 精确匹配，**不用 \.md\b 这类宽正则**——那会误伤 report.md 对象键。
$PrivateRefPatterns = @(
    'gptr-java-design', 'design/active/', 'design/done/', 'results/result-',
    'reviews/review-', 'todo/BACKLOG', 'rules/coding-discipline', 'bugfix-logs/',
    'papers-review', '设计文档 §', '规格 §',
    # 2026-09-21 补（宽扫发现的漏网形态）：**既不含 §、也没有路径前缀**的私有标识串。
    # 形如「（BACKLOG #55）」「`BACKLOG.md` #38」「CURRENT-STATE.md」—— 模式 A 的路径形态不匹配，
    # 模式 B 的裸章节号也不匹配，于是整类逃过判据（实测 10 处，其中数处进了 jar 常量池）。
    # ⚠️ 用精确形态而非裸词 'BACKLOG'：裸词在本仓也可能是**描述性提及**
    #（实例：.git-blame-ignore-revs 里写着「门禁模式集扩展后清掉的 BACKLOG 引用」—— 实测误报）。
    # 指向私有 backlog 的形态一定带编号或文件名。
    'BACKLOG #', 'BACKLOG.md', 'todo/BACKLOG',
    'CURRENT-STATE', 'glossary', 'coding-discipline')
# 裸章节号仅对**代码文件**生效：代码不属于任何带 § 编号的文档，故不可能是自引用；
# 而 .md 里的「见 §2」「[§来源…](#锚点)」是合法的文档内自引用，不得误伤。
# 文件集：有 .git 时用 `git ls-files`（**"会随公开仓发布的文件"的准确集合** —— 发布树 =
# `git archive HEAD`）；**发布树没有 .git**（`git archive` 导出的快照），故退化为文件系统遍历。
# ⚠️ 这里**不得因 git 缺失而 throw** —— 那会让发布树的使用者跑 `mvn test` 直接红。
#    2026-09-21 实测：发布树里原实现抛 `规则 11 未取到文件集`，整个门禁测试失败。
#    开发树永远暴露不了这个（它有 .git）—— 只有真在发布树上跑一次才会发现。
$skipNames = @('BUILD.md', 'docker-compose.yml', 'scripts/check-readability.ps1')
if (Test-Path (Join-Path $Root '.git')) {
    $privateRefFiles = @(& git -C $Root ls-files)
} else {
    $privateRefFiles = @(Get-ChildItem -Path $Root -Recurse -File |
        Where-Object { $_.FullName -notmatch '\\(\.git|target|node_modules|__pycache__|\.mvn|\.idea)\\' } |
        ForEach-Object { $_.FullName.Substring($Root.Length + 1) -replace '\\', '/' })
}
$privateRefFiles = @($privateRefFiles | Where-Object {
    $_ -notin $skipNames -and
    $_ -notmatch '\.(csv|png|jpe?g|gif|ico|svg|jar|bundle|zip|gz|woff2?|ttf|db)$'
})
if ($privateRefFiles.Count -eq 0) { throw '规则 11 未取到文件集：git 与文件系统遍历均为空' }
foreach ($rel11 in $privateRefFiles) {
    $full11 = Join-Path $Root $rel11
    if (-not (Test-Path $full11)) { continue }
    $codeFile11 = $rel11 -notmatch '\.md$'
    try { $lines11 = [System.IO.File]::ReadAllLines($full11) } catch { continue }
    for ($i11 = 0; $i11 -lt $lines11.Count; $i11++) {
        $line11 = $lines11[$i11]
        $hit11 = $PrivateRefPatterns | Where-Object { $line11.Contains($_) } | Select-Object -First 1
        if (-not $hit11 -and $codeFile11 -and $line11 -match '§[A-Za-z0-9]') { $hit11 = '裸章节号' }
        if ($hit11) { $violations.PrivateRef.Add("$rel11`:$($i11 + 1)  [$hit11] $($line11.Trim())") }
    }
}

# ── 汇总 ─────────────────────────────────────────────────────────────────────
# Hard = $true：违反公开规范 ⇒ 超标即构建失败
# Hard = $false：存量趋势指标 ⇒ 超标仅告警并显示差值，不中断构建
$checks = @(
    @{ Name = "单行 <=$($Limits.MaxLineLength) 字符";        Count = $violations.LineLength.Count;     Max = 0;                          Key = 'LineLength';     Hard = $true }
    @{ Name = "方法 <=$($Limits.MaxMethodLines) 行（棘轮 $($Limits.MaxMethodViolations)）"; Count = $violations.MethodLength.Count; Max = $Limits.MaxMethodViolations; Key = 'MethodLength'; Hard = $false }
    @{ Name = "块 lambda 体 <=$($Limits.MaxBlockLambdaBody) 行"; Count = $violations.BlockLambda.Count; Max = 0;                          Key = 'BlockLambda';    Hard = $true }
    @{ Name = 'lambda 内不嵌 lambda';                        Count = $violations.NestedLambda.Count;   Max = 0;                          Key = 'NestedLambda';   Hard = $true }
    @{ Name = '内联全限定名 = 0';                            Count = $violations.InlineFqn.Count;      Max = $Limits.MaxInlineFqn;       Key = 'InlineFqn';      Hard = $true }
    @{ Name = 'catch 不丢异常信息';                          Count = $violations.SilentCatch.Count;    Max = $Limits.MaxSilentCatch;     Key = 'SilentCatch';    Hard = $true }
    @{ Name = "仅凭注释的 catch（棘轮 $($Limits.MaxCommentOnlyCatch)）"; Count = $violations.CommentOnlyCatch.Count; Max = $Limits.MaxCommentOnlyCatch; Key = 'CommentOnlyCatch'; Hard = $false }
    @{ Name = '嵌套三元 = 0';                                Count = $violations.NestedTernary.Count;  Max = $Limits.MaxNestedTernary;   Key = 'NestedTernary';  Hard = $true }
    @{ Name = '未使用 import = 0';                           Count = $violations.UnusedImport.Count;   Max = $Limits.MaxUnusedImport;    Key = 'UnusedImport';   Hard = $true }
    @{ Name = "控制语句嵌套 <=$($Limits.MaxControlNesting) 层"; Count = $violations.ControlNesting.Count; Max = 0;                        Key = 'ControlNesting'; Hard = $true }
    @{ Name = "参数个数 <=$($Limits.MaxParams)（棘轮 $($Limits.MaxParamViolations)）"; Count = $violations.ParamCount.Count; Max = $Limits.MaxParamViolations; Key = 'ParamCount'; Hard = $false }
    @{ Name = "record 分量 <=$($Limits.MaxRecordComponents)（棘轮 $($Limits.MaxRecordViolations)）"; Count = $violations.RecordComponents.Count; Max = $Limits.MaxRecordViolations; Key = 'RecordComponents'; Hard = $false }
    @{ Name = '私有引用（公开物不得引用私有仓，§B2.5）'; Count = $violations.PrivateRef.Count; Max = 0; Key = 'PrivateRef'; Hard = $true }
)

$hardTotal = ($checks | Where-Object { $_.Hard }).Count

Write-Host ''
Write-Host "可读性判据门禁（rules/coding-discipline.md §B8）"
Write-Host "扫描 $($fileList.Count) 个文件 / $totalLines 行"
Write-Host "私有引用（规则 11）扫描 $($privateRefFiles.Count) 个跟踪文件"
Write-Host ''
Write-Host '  [FAIL] = 违反规范（构建失败）    [WARN] = 存量趋势变差（不中断构建）'
Write-Host ''

$failed = 0
$warned = 0
foreach ($check in $checks) {
    $ok = $check.Count -le $check.Max
    if (-not $ok) {
        if ($check.Hard) { $failed++ } else { $warned++ }
    }
    $mark = if ($ok) { 'OK  ' } elseif ($check.Hard) { 'FAIL' } else { 'WARN' }
    $delta = if (-not $ok -and -not $check.Hard) { "  超出 $($check.Count - $check.Max) 处" } else { '' }
    Write-Host ("  [{0}] {1,-32} {2,6}  (上限 {3}){4}" -f $mark, $check.Name, $check.Count, $check.Max, $delta)
    if (-not $ok -and $violations.Contains($check.Key)) {
        $list = $violations[$check.Key]
        $show = if ($ShowAll) { $list.Count } else { [Math]::Min(10, $list.Count) }
        for ($k = 0; $k -lt $show; $k++) { Write-Host "           $($list[$k])" }
        if ($show -lt $list.Count) { Write-Host "           ... 另有 $($list.Count - $show) 处（-ShowAll 查看全部）" }
    }
}

# 仅凭注释的 catch：明细落盘（便于定期查看与分批清理；默认不刷屏）
if ($violations.CommentOnlyCatch.Count -gt 0) {
    $logDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'target'
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }
    $logPath = Join-Path $logDir 'gate-silent-catch.txt'
    $violations.CommentOnlyCatch | Set-Content -Path $logPath -Encoding UTF8
    Write-Host ''
    Write-Host "  仅凭注释的 catch 明细（$($violations.CommentOnlyCatch.Count) 条）-> $logPath"
    Write-Host '    （这些 catch 只有注释、没有实质动作；注释说明不了"失败去了哪"）'
}

if ($violations.PrivateRef.Count -gt 0) {
    $logDir11 = Join-Path (Split-Path $PSScriptRoot -Parent) 'target'
    if (-not (Test-Path $logDir11)) { New-Item -ItemType Directory -Path $logDir11 -Force | Out-Null }
    $logPath11 = Join-Path $logDir11 'gate-private-refs.txt'
    $violations.PrivateRef | Set-Content -Path $logPath11 -Encoding UTF8
    Write-Host ''
    Write-Host "  私有引用明细（$($violations.PrivateRef.Count) 条）-> $logPath11"
    Write-Host '    （公开物不得引用私有仓文档；改法见 rules/coding-discipline.md §B2.5）'
}

Write-Host ''
if ($failed -eq 0) {
    $tail = if ($warned -gt 0) { "；趋势告警 $warned 项（不阻断构建）" } else { '' }
    Write-Host "通过：硬判据 $($hardTotal - $failed) / $hardTotal$tail"
    exit 0
}
Write-Host "未通过：$failed 项硬判据超标"
exit 1
