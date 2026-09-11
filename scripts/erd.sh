#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# erd.sh — 실행 중인 MySQL 컨테이너(mysql-8)에서 스키마를 읽어 mermaid ERD 생성
#
# 사용법:
#   ./scripts/erd.sh                # board 스키마 → 화면 출력
#   ./scripts/erd.sh board_class    # 다른 스키마 지정
#   ./scripts/erd.sh board > erd.md # 파일로 저장(Obsidian/GitHub에서 다이어그램 렌더)
#
# 환경변수(선택): CONTAINER(기본 mysql-8), DB_PASSWORD(기본 1234)
# 원리: information_schema 두 개를 조회해 mermaid erDiagram 텍스트로 변환한다.
#   - columns            → 테이블·컬럼·타입·키(PRI/UNI)
#   - key_column_usage   → FK 관계선(referenced_table_name)
# 읽기 전용(SELECT만)이라 DB에 아무 영향이 없다.
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail

DB="${1:-board}"
CONTAINER="${CONTAINER:-mysql-8}"
PASS="${DB_PASSWORD:-1234}"

# 컨테이너 안의 mysql 클라이언트로 조회 (-N 헤더 없음, -B 탭 구분)
q() { docker exec "$CONTAINER" mysql -uroot -p"$PASS" -N -B -e "$1" 2>/dev/null; }

COLS=$(q "SELECT c.table_name, c.column_name, c.data_type, c.column_key
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema=c.table_schema AND t.table_name=c.table_name
          WHERE c.table_schema='${DB}' AND t.table_type='BASE TABLE'
          ORDER BY c.table_name, c.ordinal_position;")

FKS=$(q "SELECT table_name, column_name, referenced_table_name
         FROM information_schema.key_column_usage
         WHERE table_schema='${DB}' AND referenced_table_name IS NOT NULL
         ORDER BY referenced_table_name, table_name;")

[ -n "$COLS" ] || { echo "스키마 '${DB}'에서 테이블을 찾지 못함 (컨테이너=${CONTAINER})" >&2; exit 1; }

# 두 결과(TSV)를 mermaid erDiagram으로 조립
COLS="$COLS" FKS="$FKS" python3 - <<'PY'
import os, collections

cols = collections.OrderedDict()
for line in os.environ["COLS"].splitlines():
    p = line.split("\t")
    if len(p) < 3:
        continue
    t, c, ty = p[0], p[1], p[2]
    key = p[3] if len(p) > 3 else ""
    cols.setdefault(t, []).append((c, ty, key))

fks, fkset = [], set()
for line in os.environ["FKS"].splitlines():
    p = line.split("\t")
    if len(p) == 3:
        fks.append(p)
        fkset.add((p[0], p[1]))

print("```mermaid")
print("erDiagram")
for t, c, ref in fks:                       # 관계선: 부모 ||--o{ 자식 (1:N)
    print(f'  {ref} ||--o{{ {t} : "{c}"')
print()
for t, cs in cols.items():                  # 테이블 블록: 타입 컬럼명 PK/FK/UK
    print(f"  {t} {{")
    for c, ty, key in cs:
        mark = "PK" if key == "PRI" else ("FK" if (t, c) in fkset else ("UK" if key == "UNI" else ""))
        print(f"    {ty} {c} {mark}".rstrip())
    print("  }")
print("```")
PY
