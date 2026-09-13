import re

base = 'app/src/main/java/com/chaomixian/vflow/ui/chat/'
router = open(base + 'ChatAgentSkillRouter.kt', encoding='utf-8').read()
registry = open(base + 'ChatAgentToolRegistry.kt', encoding='utf-8').read()

prompt = [m.group(1).strip() for m in re.finditer(r'appendLine\("([^"]{20,})"\)', router)]
tool_desc = [m.group(1).strip() for m in re.finditer(r'append\("([^"]{20,})"\)', registry)]
m = re.search(r'buildVariablePassingGuide\(\): String \{\s*return """(.*?)"""', registry, re.S)
guide = [l.strip() for l in m.group(1).split('\n') if len(l.strip()) > 20] if m else []

context = prompt + tool_desc + guide
ctx_words = set()
for t in context:
    ctx_words.update(re.findall(r'[a-z]{4,}', t.lower()))

# 按行扫描：找 `private val ... = ChatAgentSkillDefinition(` 直到 `""".trimIndent()`
lines = router.split('\n')
skills = []
i = 0
while i < len(lines):
    if '= ChatAgentSkillDefinition(' in lines[i] and lines[i].strip().startswith('private val'):
        name = lines[i].strip().split()[2]
        j = i
        sid = None
        body_start = None
        while j < len(lines):
            if sid is None and 'id = "' in lines[j]:
                sid = re.search(r'id = "([^"]+)"', lines[j]).group(1)
            if 'instructions = """' in lines[j]:
                body_start = j + 1
            if '""".trimIndent()' in lines[j] and body_start:
                break
            j += 1
        body = lines[body_start:j]
        skills.append((name, sid, body))
        i = j
    else:
        i += 1

print(f'上下文：prompt {len(prompt)} 行 / 工具描述 {len(tool_desc)} 段 / guide {len(guide)} 行')
print(f'技能数：{len(skills)}')
print('=' * 98)

missing_total = 0
for name, sid, body in skills:
    rows = [l.strip() for l in body if len(l.strip()) > 15]
    print(f'\n### {sid}  ({len(rows)} 行)')
    for line in rows:
        words = [w for w in re.findall(r'[a-z]{4,}', line.lower())]
        miss = [w for w in words if w not in ctx_words]
        ratio = len(miss) / max(len(words), 1)
        if ratio > 0.35:
            missing_total += 1
            print(f'  [MISSING] {line[:86]}')
            print(f'            未命中: {miss}')
print(f'\n===> 疑似独有行数：{missing_total}')
