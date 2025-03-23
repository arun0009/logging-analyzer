import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

record Issue(String fileName, int lineNumber, String description) {
    @Override
    public String toString() {
        File file = new File(fileName);
        String absolutePath = file.getAbsolutePath().replace("\\", "/");
        return String.format("file://%s:%d - %s", absolutePath, lineNumber, description);
    }
}

public class LoggingAnalyzer {
    private static final int EXCESSIVE_LOG_THRESHOLD = 5;
    private static final int MAX_MESSAGE_LENGTH = 200;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java LoggingAnalyzer <project-directory>");
            System.exit(1);
        }

        File projectDir = new File(args[0]);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            System.err.println("Error: " + args[0] + " is not a valid directory");
            System.exit(1);
        }

        List<Issue> issues = new ArrayList<>();
        analyzeDirectory(projectDir, issues);

        System.out.println("""
            Logging Issues Report:
            """);
        if (issues.isEmpty()) {
            System.out.println("No issues found.");
        } else {
            issues.forEach(System.out::println);
            System.out.printf("\nTotal issues found: %d%n", issues.size());
        }
    }

    private static void analyzeDirectory(File dir, List<Issue> issues) throws Exception {
        Files.walk(dir.toPath())
            .filter(path -> path.toString().endsWith(".java"))
            .map(Path::toFile)
            .forEach(file -> {
                try {
                    analyzeFile(file, issues);
                } catch (Exception e) {
                    //System.err.println("Failed to analyze " + file + ": " + e.getMessage());
                }
            });
    }

    private static void analyzeFile(File file, List<Issue> issues) throws Exception {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        StaticJavaParser.setConfiguration(parserConfiguration);
        CompilationUnit cu = StaticJavaParser.parse(file);
        String fileName = file.getPath();

        checkPrintStackTrace(cu, fileName, issues);
        checkLoggingAndRethrowing(cu, fileName, issues);
        checkLoggingCompleteObjects(cu, fileName, issues);
        checkExcessiveLogging(cu, fileName, issues);
        checkSystemOutErr(cu, fileName, issues);
        checkLoggingWithoutContext(cu, fileName, issues);
        checkHardcodedLogMessages(cu, fileName, issues);
        checkRepeatedLogStatements(cu, fileName, issues);
        checkLoggingInLoops(cu, fileName, issues);
        checkOverlyVerboseMessages(cu, fileName, issues);
        checkInconsistentLogLevels(cu, fileName, issues);
        checkLoggingCaughtExceptionsWithoutAction(cu, fileName, issues);
        checkImproperSLF4JPlaceholders(cu, fileName, issues);
        checkOveruseOfExceptionStackTraces(cu, fileName, issues);
    }

    private static void checkPrintStackTrace(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getNameAsString().equals("printStackTrace")) {
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "Uses e.printStackTrace() instead of SLF4J/Log4j2"))
                );
            }
        });
    }

    private static void checkLoggingAndRethrowing(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (isLogMethod(call)) {
                call.findAncestor(BlockStmt.class).ifPresent(block -> {
                    if (block.getStatements().stream().anyMatch(Statement::isThrowStmt)) {
                        call.getRange().ifPresent(range ->
                            issues.add(new Issue(fileName, range.begin.line, "Logging and rethrowing detected"))
                        );
                    }
                });
            }
        });
    }

private static void checkLoggingCompleteObjects(CompilationUnit cu, String fileName, List<Issue> issues) {
    cu.findAll(MethodCallExpr.class).forEach(call -> {
        if (isLogMethod(call) && !call.getArguments().isEmpty()) {
            for (int i = 0; i < call.getArguments().size(); i++) {
                var arg = call.getArguments().get(i);
                if (!arg.isLiteralExpr() && !arg.isStringLiteralExpr() && !arg.isBinaryExpr() &&
                    !arg.isIntegerLiteralExpr() && !arg.isBooleanLiteralExpr() && !arg.isDoubleLiteralExpr()) {
                    String argStr = arg.toString().toLowerCase();
                    if ((argStr.contains("list") || argStr.contains("map") || argStr.contains("set") || 
                         argStr.contains("[") || argStr.matches(".*[A-Z].*")) && 
                        !argStr.matches(".*(message|name|data|id|key|value|string).*")) {
                        int finalI = i;
                        call.getRange().ifPresent(range ->
                            issues.add(new Issue(fileName, range.begin.line,
                                finalI == 0 ? "Logging complete object as message" : "Logging potentially large object as argument"))
                        );
                    }
                }
            }
        }
    });
}

    private static void checkExcessiveLogging(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            var logCalls = method.findAll(MethodCallExpr.class, LoggingAnalyzer::isLogMethod);
            if (logCalls.size() > EXCESSIVE_LOG_THRESHOLD) {
                method.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line,
                        "Excessive logging: %d log statements in method %s".formatted(logCalls.size(), method.getNameAsString())))
                );
            }
        });
    }

    private static void checkSystemOutErr(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            var scope = call.getScope().toString();
            var name = call.getNameAsString();
            if ((scope.contains("System.out") || scope.contains("System.err")) && name.equals("println")) {
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "Uses %s.%s instead of SLF4J/Log4j2".formatted(scope, name)))
                );
            }
        });
    }

    private static void checkLoggingWithoutContext(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (isLogMethod(call) && !call.getArguments().isEmpty()) {
                var arg = call.getArgument(0);
                switch (arg) {
                    case StringLiteralExpr str when str.getValue().trim().isEmpty() ->
                        call.getRange().ifPresent(range ->
                            issues.add(new Issue(fileName, range.begin.line, "Logging without meaningful context (empty message)"))
                        );
                    case null, default -> {
                        if (!arg.isStringLiteralExpr()) {
                            call.getRange().ifPresent(range ->
                                issues.add(new Issue(fileName, range.begin.line, "Logging exception without message"))
                            );
                        }
                    }
                }
            }
        });
    }

    private static void checkHardcodedLogMessages(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (isLogMethod(call) && call.getArguments().size() == 1 && call.getArgument(0).isStringLiteralExpr()) {
                var msg = call.getArgument(0).asStringLiteralExpr().getValue();
                if (!msg.contains("{}") && !msg.contains("%s") && !msg.contains("${")) {
                    call.getRange().ifPresent(range ->
                        issues.add(new Issue(fileName, range.begin.line, "Hardcoded log message without placeholders"))
                    );
                }
            }
        });
    }

    private static void checkRepeatedLogStatements(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            var logCount = new HashMap<String, Integer>();
            method.findAll(MethodCallExpr.class, LoggingAnalyzer::isLogMethod)
                .forEach(call -> logCount.merge(call.toString(), 1, Integer::sum));
            logCount.forEach((logText, count) -> {
                if (count > 1) {
                    method.getRange().ifPresent(range ->
                        issues.add(new Issue(fileName, range.begin.line,
                            "Repeated log statement (%d times) in method %s: %s".formatted(count, method.getNameAsString(), logText)))
                    );
                }
            });
        });
    }

    private static void checkLoggingInLoops(CompilationUnit cu, String fileName, List<Issue> issues) {
        var loops = new ArrayList<Statement>();
        cu.findAll(ForStmt.class).forEach(loops::add);
        cu.findAll(ForEachStmt.class).forEach(loops::add);
        cu.findAll(WhileStmt.class).forEach(loops::add);

        for (var loop : loops) {
            loop.findAll(MethodCallExpr.class, LoggingAnalyzer::isLogMethod).forEach(call ->
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "Logging inside loop (potential log flood)"))
                )
            );
        }
    }

    private static void checkOverlyVerboseMessages(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (isLogMethod(call) && call.getArguments().size() >= 1 && call.getArgument(0).isStringLiteralExpr()) {
                var msg = call.getArgument(0).asStringLiteralExpr().getValue();
                if (msg.length() > MAX_MESSAGE_LENGTH) {
                    call.getRange().ifPresent(range ->
                        issues.add(new Issue(fileName, range.begin.line, "Overly verbose log message (%d chars)".formatted(msg.length())))
                    );
                }
            }
        });
    }

    private static void checkInconsistentLogLevels(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            var name = call.getNameAsString();
            if (name.equals("info") && call.getArguments().size() > 1 && call.getArgument(1).isNameExpr() && 
                call.getArgument(1).asNameExpr().getNameAsString().contains("Exception")) {
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "Inconsistent log level: info used for exception"))
                );
            } else if (name.equals("warn") && call.getArguments().size() == 1 && 
                call.getArgument(0).isStringLiteralExpr() && 
                call.getArgument(0).asStringLiteralExpr().getValue().contains("minor")) {
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "Inconsistent log level: warn used for minor issue"))
                );
            }
        });
    }

    private static void checkLoggingCaughtExceptionsWithoutAction(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(TryStmt.class).forEach(tryStmt -> {
            tryStmt.getCatchClauses().forEach(catchClause -> {
                var block = catchClause.getBody();
                var logCalls = block.findAll(MethodCallExpr.class, LoggingAnalyzer::isLogMethod);
                if (!logCalls.isEmpty() && 
                    !block.getStatements().stream().anyMatch(Statement::isThrowStmt) &&
                    !block.getStatements().stream().anyMatch(stmt -> stmt.isReturnStmt() || stmt.isBreakStmt())) {
                    logCalls.forEach(call ->
                        call.getRange().ifPresent(range ->
                            issues.add(new Issue(fileName, range.begin.line, "Logging caught exception without action"))
                        )
                    );
                }
            });
        });
    }

    private static void checkImproperSLF4JPlaceholders(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (isLogMethod(call) && call.getArguments().size() == 1 && 
                call.getArgument(0).isBinaryExpr() && 
                call.getArgument(0).asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "String concatenation instead of SLF4J placeholders"))
                );
            }
        });
    }

    private static void checkOveruseOfExceptionStackTraces(CompilationUnit cu, String fileName, List<Issue> issues) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if ((call.getNameAsString().equals("error") || call.getNameAsString().equals("warn")) && 
                call.getArguments().size() > 1 && 
                call.getArguments().get(call.getArguments().size() - 1).isNameExpr() && 
                call.getArguments().get(call.getArguments().size() - 1).toString().contains("Exception")) {
                call.getRange().ifPresent(range ->
                    issues.add(new Issue(fileName, range.begin.line, "Overuse of exception stack trace in log"))
                );
            }
        });
    }

    private static boolean isLogMethod(MethodCallExpr call) {
        var name = call.getNameAsString();
        return switch (name) {
            case "debug", "info", "warn", "error", "trace", "fatal" -> true;
            default -> false;
        };
    }
}