#!/usr/bin/env node
let data = "";
process.stdin.on("data", (chunk) => (data += chunk));
process.stdin.on("end", () => {
  let command = "";
  try {
    const parsed = JSON.parse(data);
    command = parsed?.tool_input?.command ?? "";
  } catch (e) {
    process.exit(0);
  }

  const dangerousPatterns = [
    /git push/,
    /git reset --hard/,
    /git clean -fd/,
    /git clean -f/,
    /git branch -D/,
    /git checkout \./,
    /git restore \./,
    /push --force/,
    /reset --hard/,
  ];

  for (const pattern of dangerousPatterns) {
    if (pattern.test(command)) {
      console.error(
        `BLOCKED: '${command}' matches dangerous pattern '${pattern.source}'. The user has prevented you from doing this.`
      );
      process.exit(2);
    }
  }

  process.exit(0);
});
