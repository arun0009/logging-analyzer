# logging-analyzer
A static analysis tool to detect logging practices that bloat logs or deviate from SLF4J/Log4j2 standards in Java projects.

## Overview
LoggingAnalyzer uses JavaParser to scan your Java codebase and report issues like excessive logging, unformatted objects, or non-standard practices. 
Each issue in the report includes a clickable file:// link to jump directly to the problematic code.

## Features
* Input: Specify a project directory via command-line argument.
* Output: A report with clickable links to issues (e.g., file:///path/to/file.java:line).

* Checks:
  1. printStackTrace() instead of logging.
  2. Logging and rethrowing exceptions.
  3. Logging complete/potentially large objects.
  4. Excessive log statements (>5 per method).
  5. System.out/System.err instead of SLF4J.
  6. Logging without context (empty or exception-only).
  7. Hardcoded messages without placeholders.
  8. Repeated log statements in a method.
  9. Logging inside loops.
  10. Overly verbose messages (>200 chars).
  11. Inconsistent log levels (e.g., info for exceptions).
  12. Logging caught exceptions without action.
  13. String concatenation instead of SLF4J placeholders.
  14. Overuse of exception stack traces.

## Installation

Clone the repo:
```bash
  git clone https://github.com/arun0009/logging-analyzer.git
  cd logging-analyzer
  mvn clean install
```

## Usage

Run the tool with a project directory as an argument:
```bash
mvn exec:java -Darg=/path/to/your/project
```
## Example Output

Logging Issues Report:
```
file:///home/user/project/src/MyClass.java:15 - Uses e.printStackTrace() instead of SLF4J/Log4j2
file:///home/user/project/src/MyService.java:22 - Logging and rethrowing detected
file:///home/user/project/src/MyUtil.java:33 - Logging inside loop (potential log flood)

Total issues found: 3
```