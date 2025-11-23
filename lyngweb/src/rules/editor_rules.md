
This file describes the Enter/indent rules used by the `lyngweb` module and the dependent `site` project editor, `EditorWithOverlay`.

## Indents and tabs

- Block size ("tab size") is 4 spaces.
- Tabs are converted to spaces: before loading text and on paste/typing, a tab is replaced by 4 spaces.
- Indent of a line is the count of leading ASCII spaces (0x20) on that line.
- Increasing indent sets it to the next multiple of 4.
- Decreasing indent (undent/dedent) sets it to the previous multiple of 4, but never below 0.
- "Indent level" means how many 4-space tab stops precede the caret. An indent that is already 0 cannot be decreased further.

## Newlines

- Internally, logic should treat `\r\n` (CRLF) as a single newline for all rules. Tests may use LF or CRLF.

## Definitions used below

- "Current line" means the line that contains the caret (or the start of the selection).
- "Last non-whitespace before caret" means the last non-space character on the current line at an index strictly less than the caret position (trailing spaces are ignored).
- "Brace-only line" means that the line’s trimmed text equals exactly `}`.

## Enter key rules

The following rules govern what happens when the Enter key is pressed. Each rule also specifies the caret position after the operation.

1) After an opening brace `{`
- If the last non-whitespace before the caret on the current line is `{`, insert a newline and set the new line indent to one block (4 spaces) more than the current line’s indent. Place the caret at that new indent.

2) On a brace-only line `}` (caret on the same line)
- If the current line’s trimmed text is exactly `}`, decrease that line’s indent by one block (not below 0), then insert a newline. The newly inserted line uses the (decreased) indent. Place the caret at the start of the newly inserted line.

3) End of a line before a brace-only next line
- If the caret is at the end of line N, and line N+1 is a brace-only line (ignoring leading spaces), do not insert an extra blank line. Instead, decrease the indent of line N+1 by one block (not below 0) and move the caret to the start of that (dedented) `}` line.

4) Between braces on the same line `{|}`
- If the character immediately before the caret is `{` and the character immediately after the caret is `}`, split into two lines: insert a newline so that the inner (new) line is indented by one block more than the current line, and keep `}` on the following line with the current line’s indent. Place the caret at the start of the inner line (one block deeper).

5) After `}` with only spaces until end-of-line
- If the last non-whitespace before the caret is `}` and the remainder of the current line up to EOL contains only spaces, insert a newline whose indent is one block less than the current line’s indent (not below 0). Place the caret at that indent.

6) Default smart indent
- In all other cases, insert a newline and keep the same indent as the current line. Place the caret at that indent.

## Selections

- If a selection is active, pressing Enter replaces the selection with a newline, and the indent for the new line is computed from the indent of the line containing the caret’s start (anchor). The caret is placed at the start of the inserted line at that indent.

## Notes and examples

Example for rules 1, 2, and 6:
```
1<enter>2<enter>{<enter>3<enter>4<enter>}<enter>5
```

Results in:
```
1
2
{
    3
    4
}
5
```

## Clarifications and constraints

- All dedent operations clamp at 0 spaces.
- Only ASCII spaces count toward indentation; other Unicode whitespace is treated as content.
- Trailing spaces are ignored when evaluating the "last non-whitespace before caret" condition.

## Recommended tests to cover edge cases

- Enter at EOL when the next line is `}` with leading spaces at indents 0, 2, 4, 8.
- Enter between `{|}` on the same line.
- Enter after `}` with only trailing spaces until EOL.
- Enter at the start of a brace-only line (caret at columns 0, 2, 4).
- Enter on a line containing `} else {` at various caret positions (default to smart indent when not brace-only).
- Enter on a whitespace-only line (default smart indent).
- LF and CRLF inputs for all above.

