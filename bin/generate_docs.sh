#!/bin/bash
#
# Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#

set -e

# Configuration
DOCS_DIR="docs"
OUTPUT_DIR="distributables"
TEMP_DIR="build/temp_docs"
MERGED_MD="$TEMP_DIR/merged.md"
OUTPUT_HTML="$OUTPUT_DIR/lyng_documentation.html"

mkdir -p "$OUTPUT_DIR"
mkdir -p "$TEMP_DIR"

# Files that should come first in specific order
PRIORITY_FILES=(
    "tutorial.md"
    "OOP.md"
    "advanced_topics.md"
    "declaring_arguments.md"
    "scopes_and_closures.md"
    "exceptions_handling.md"
    "when.md"
    "parallelism.md"
    "Testing.md"
)

# Files that should come next (reference)
REFERENCE_FILES=(
    "Collection.md"
    "Iterable.md"
    "Iterator.md"
    "List.md"
    "Set.md"
    "Map.md"
    "Array.md"
    "Buffer.md"
    "RingBuffer.md"
    "Range.md"
    "Real.md"
    "Regex.md"
    "math.md"
    "time.md"
)

# Files that are about integration/tools
INTEGRATION_FILES=(
    "serialization.md"
    "json_and_kotlin_serialization.md"
    "embedding.md"
    "lyng_cli.md"
    "lyng.io.fs.md"
    "formatter.md"
    "EfficientIterables.md"
)

# Tracking processed files to avoid duplicates
PROCESSED_PATHS=()

is_excluded() {
    local full_path="$1"
    if grep -q "excludeFromIndex" "$full_path"; then
        return 0 # true in bash
    fi
    return 1 # false
}

process_file() {
    local rel_path="$1"
    local full_path="$DOCS_DIR/$rel_path"
    
    if [[ ! -f "$full_path" ]]; then
        return
    fi
    
    if is_excluded "$full_path"; then
        echo "Skipping excluded: $rel_path"
        return
    fi

    # Check for duplicates
    for p in "${PROCESSED_PATHS[@]}"; do
        if [[ "$p" == "$rel_path" ]]; then
            return
        fi
    done
    PROCESSED_PATHS+=("$rel_path")

    echo "Processing: $rel_path"
    
    # 1. Add an anchor for the file based on its path
    local anchor_name=$(echo "$rel_path" | sed 's/\//_/g')
    echo "<div id=\"$anchor_name\"></div>" >> "$MERGED_MD"
    echo "" >> "$MERGED_MD"
    
    # 2. Append content with fixed links
    # - [text](file.md) -> [text](#file.md)
    # - [text](dir/file.md) -> [text](#dir_file.md)
    # - [text](file.md#anchor) -> [text](#anchor)
    # - Fix image links: [alt](../images/...) -> [alt](images/...) if needed, but none found yet.
    
    cat "$full_path" | \
    perl -pe 's/\[([^\]]+)\]\(([^)]+)\.md\)/"[$1](#" . ($2 =~ s|\/|_|gr) . ".md)"/ge' | \
    perl -pe 's/\[([^\]]+)\]\(([^)]+)\.md#([^)]+)\)/[$1](#$3)/g' >> "$MERGED_MD"
    
    echo -e "\n\n---\n\n" >> "$MERGED_MD"
}

# Start with an empty merged file
echo "% Lyng Language Documentation" > "$MERGED_MD"
echo "" >> "$MERGED_MD"

# 1. Process priority files
for f in "${PRIORITY_FILES[@]}"; do
    process_file "$f"
done

# 2. Process reference files
for f in "${REFERENCE_FILES[@]}"; do
    process_file "$f"
done

# 3. Process integration files
for f in "${INTEGRATION_FILES[@]}"; do
    process_file "$f"
done

# 4. Process remaining files in docs root
for f in "$DOCS_DIR"/*.md; do
    rel_f=${f#"$DOCS_DIR/"}
    process_file "$rel_f"
done

# 5. Process remaining files in subdirs (like samples)
find "$DOCS_DIR" -name "*.md" | sort | while read -r f; do
    rel_f=${f#"$DOCS_DIR/"}
    process_file "$rel_f"
done

echo "Running pandoc to generate $OUTPUT_HTML..."

# Use a basic but clean CSS
pandoc "$MERGED_MD" -o "$OUTPUT_HTML" \
    --toc --toc-depth=2 \
    --standalone \
    --embed-resources \
    --metadata title="Lyng Language Documentation" \
    --css <(echo "
        body { 
            font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Helvetica, Arial, sans-serif;
            line-height: 1.6; 
            max-width: 1000px; 
            margin: 0 auto; 
            padding: 2em; 
            color: #24292e;
            background-color: #fff;
        }
        code { 
            background-color: rgba(27,31,35,0.05); 
            padding: 0.2em 0.4em; 
            border-radius: 3px; 
            font-family: SFMono-Regular, Consolas, \"Liberation Mono\", Menlo, monospace;
            font-size: 85%;
        }
        pre { 
            background-color: #f6f8fa; 
            padding: 16px; 
            overflow: auto; 
            border-radius: 3px;
            line-height: 1.45;
        }
        pre code {
            background-color: transparent;
            padding: 0;
            font-size: 100%;
        }
        h1 { 
            border-bottom: 1px solid #eaecef; 
            padding-bottom: 0.3em; 
            margin-top: 24px;
            margin-bottom: 16px;
            font-weight: 600;
        }
        h2 { 
            border-bottom: 1px solid #eaecef; 
            padding-bottom: 0.3em; 
            margin-top: 24px;
            margin-bottom: 16px;
            font-weight: 600;
        }
        hr { 
            height: 0.25em;
            padding: 0;
            margin: 24px 0;
            background-color: #e1e4e8;
            border: 0;
        }
        blockquote {
            padding: 0 1em;
            color: #6a737d;
            border-left: 0.25em solid #dfe2e5;
            margin: 0 0 16px 0;
        }
        nav#TOC { 
            background: #f9f9f9; 
            padding: 1em; 
            border: 1px solid #eee; 
            margin-bottom: 2.5em; 
            border-radius: 6px;
        }
        nav#TOC ul { 
            list-style: none; 
            padding-left: 1.5em; 
        }
        nav#TOC > ul {
            padding-left: 0;
        }
        table {
            border-spacing: 0;
            border-collapse: collapse;
            margin-top: 0;
            margin-bottom: 16px;
        }
        table th, table td {
            padding: 6px 13px;
            border: 1px solid #dfe2e5;
        }
        table tr {
            background-color: #fff;
            border-top: 1px solid #c6cbd1;
        }
        table tr:nth-child(2n) {
            background-color: #f6f8fa;
        }
    ")

echo "-------------------------------------------------------"
echo "Done! Documentation generated successfully."
echo "Location: $OUTPUT_HTML"
echo "-------------------------------------------------------"
