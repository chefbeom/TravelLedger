package com.playdata.calen.ledger.search;

import com.playdata.calen.common.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LedgerSearchExpression {

    private static final int MAX_QUERY_LENGTH = 240;
    private static final int MAX_TERM_LENGTH = 80;
    private static final int MAX_TERM_COUNT = 16;
    private static final int MAX_NODE_COUNT = 64;

    private final Node root;
    private final int termCount;
    private final int nodeCount;

    private LedgerSearchExpression(Node root, int termCount, int nodeCount) {
        this.root = root;
        this.termCount = termCount;
        this.nodeCount = nodeCount;
    }

    public static LedgerSearchExpression empty() {
        return new LedgerSearchExpression(null, 0, 0);
    }

    public static LedgerSearchExpression parse(String rawQuery, boolean whitespaceAnd) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return empty();
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("검색식은 240자 이하로 입력해 주세요.");
        }
        if (!whitespaceAnd && !hasAdvancedSyntax(query)) {
            return singleTerm(query);
        }
        Parser parser = new Parser(tokenize(query), whitespaceAnd);
        LedgerSearchExpression expression = parser.parse();
        if (expression.termCount > MAX_TERM_COUNT || expression.nodeCount > MAX_NODE_COUNT) {
            throw new BadRequestException("검색 조건이 너무 많습니다. 핵심 단어 위주로 줄여 주세요.");
        }
        return expression;
    }

    public boolean isEmpty() {
        return root == null;
    }

    public Node root() {
        return root;
    }

    public int termCount() {
        return termCount;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public interface Node {
    }

    public record TermNode(String term) implements Node {
        public TermNode {
            Objects.requireNonNull(term, "term");
        }
    }

    public record AndNode(List<Node> children) implements Node {
        public AndNode {
            children = List.copyOf(children);
        }
    }

    public record OrNode(List<Node> children) implements Node {
        public OrNode {
            children = List.copyOf(children);
        }
    }

    public record NotNode(Node child) implements Node {
        public NotNode {
            Objects.requireNonNull(child, "child");
        }
    }

    private static LedgerSearchExpression singleTerm(String rawTerm) {
        String term = normalizeTerm(rawTerm);
        if (term.isBlank()) {
            return empty();
        }
        return new LedgerSearchExpression(new TermNode(term), 1, 1);
    }

    private static boolean hasAdvancedSyntax(String query) {
        boolean atTokenBoundary = true;
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (Character.isWhitespace(ch)) {
                atTokenBoundary = true;
                continue;
            }
            if (ch == '+' || ch == '|' || ch == '(' || ch == ')' || ch == '"' || ch == '\'') {
                return true;
            }
            if (ch == '-' && atTokenBoundary && i + 1 < query.length() && !Character.isWhitespace(query.charAt(i + 1))) {
                return true;
            }
            atTokenBoundary = false;
        }
        return false;
    }

    private static List<Token> tokenize(String query) {
        List<Token> tokens = new ArrayList<>();
        boolean atTokenBoundary = true;
        int index = 0;
        while (index < query.length()) {
            char ch = query.charAt(index);
            if (Character.isWhitespace(ch)) {
                atTokenBoundary = true;
                index++;
                continue;
            }
            switch (ch) {
                case '+' -> {
                    tokens.add(new Token(TokenType.PLUS, "+"));
                    atTokenBoundary = true;
                    index++;
                }
                case '|' -> {
                    tokens.add(new Token(TokenType.OR, "|"));
                    atTokenBoundary = true;
                    index++;
                }
                case '(' -> {
                    tokens.add(new Token(TokenType.LPAREN, "("));
                    atTokenBoundary = true;
                    index++;
                }
                case ')' -> {
                    tokens.add(new Token(TokenType.RPAREN, ")"));
                    atTokenBoundary = false;
                    index++;
                }
                case '-', '\'', '"' -> {
                    if (ch == '-' && atTokenBoundary) {
                        if (index + 1 < query.length() && !Character.isWhitespace(query.charAt(index + 1))) {
                            tokens.add(new Token(TokenType.NOT, "-"));
                            atTokenBoundary = true;
                            index++;
                        } else {
                            throw new BadRequestException("제외할 검색어는 -취소처럼 - 바로 뒤에 입력해 주세요.");
                        }
                    } else if (ch == '\'' || ch == '"') {
                        ReadResult quoted = readQuoted(query, index, ch);
                        tokens.add(new Token(TokenType.WORD, normalizeTerm(quoted.text())));
                        atTokenBoundary = false;
                        index = quoted.nextIndex();
                    } else {
                        ReadResult word = readWord(query, index);
                        tokens.add(new Token(TokenType.WORD, normalizeTerm(word.text())));
                        atTokenBoundary = false;
                        index = word.nextIndex();
                    }
                }
                default -> {
                    ReadResult word = readWord(query, index);
                    tokens.add(new Token(TokenType.WORD, normalizeTerm(word.text())));
                    atTokenBoundary = false;
                    index = word.nextIndex();
                }
            }
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private static ReadResult readWord(String query, int startIndex) {
        StringBuilder builder = new StringBuilder();
        int index = startIndex;
        while (index < query.length()) {
            char ch = query.charAt(index);
            if (Character.isWhitespace(ch) || ch == '+' || ch == '|' || ch == '(' || ch == ')' || ch == '"' || ch == '\'') {
                break;
            }
            if (ch == '-' && builder.length() == 0 && index == startIndex) {
                break;
            }
            builder.append(ch);
            index++;
        }
        return new ReadResult(builder.toString(), index);
    }

    private static ReadResult readQuoted(String query, int startIndex, char quote) {
        StringBuilder builder = new StringBuilder();
        int index = startIndex + 1;
        boolean escaped = false;
        while (index < query.length()) {
            char ch = query.charAt(index);
            if (escaped) {
                builder.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == quote) {
                return new ReadResult(builder.toString(), index + 1);
            } else {
                builder.append(ch);
            }
            index++;
        }
        throw new BadRequestException("검색식의 따옴표가 닫히지 않았습니다.");
    }

    private static String normalizeTerm(String rawTerm) {
        String term = rawTerm == null ? "" : rawTerm.trim().replaceAll("\\s+", " ");
        if (term.length() > MAX_TERM_LENGTH) {
            throw new BadRequestException("검색 단어는 80자 이하로 입력해 주세요.");
        }
        return term;
    }

    private enum TokenType {
        WORD,
        PLUS,
        OR,
        NOT,
        LPAREN,
        RPAREN,
        EOF
    }

    private record Token(TokenType type, String text) {
    }

    private record ReadResult(String text, int nextIndex) {
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final boolean whitespaceAnd;
        private int current;
        private int termCount;
        private int nodeCount;

        private Parser(List<Token> tokens, boolean whitespaceAnd) {
            this.tokens = tokens;
            this.whitespaceAnd = whitespaceAnd;
        }

        private LedgerSearchExpression parse() {
            Node root = parseOr();
            if (!isAtEnd()) {
                if (peek().type == TokenType.RPAREN) {
                    throw new BadRequestException("검색식의 괄호 위치를 확인해 주세요.");
                }
                throw new BadRequestException("검색어 사이에는 + 또는 | 연산자를 사용해 주세요.");
            }
            return new LedgerSearchExpression(root, termCount, nodeCount);
        }

        private Node parseOr() {
            Node node = parseAnd();
            List<Node> children = new ArrayList<>();
            children.add(node);
            while (match(TokenType.OR)) {
                children.add(parseAnd());
            }
            if (children.size() == 1) {
                return node;
            }
            return newNode(new OrNode(children));
        }

        private Node parseAnd() {
            Node node = parseUnary();
            List<Node> children = new ArrayList<>();
            children.add(node);
            while (true) {
                if (match(TokenType.PLUS)) {
                    children.add(parseUnary());
                } else if (canImplicitAnd()) {
                    children.add(parseUnary());
                } else {
                    break;
                }
            }
            if (children.size() == 1) {
                return node;
            }
            return newNode(new AndNode(children));
        }

        private boolean canImplicitAnd() {
            TokenType next = peek().type;
            if (next == TokenType.NOT) {
                return true;
            }
            return whitespaceAnd && (next == TokenType.WORD || next == TokenType.LPAREN);
        }

        private Node parseUnary() {
            if (match(TokenType.NOT)) {
                return newNode(new NotNode(parseUnary()));
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            if (match(TokenType.WORD)) {
                String term = previous().text;
                if (term.isBlank()) {
                    throw new BadRequestException("검색어를 입력해 주세요.");
                }
                termCount++;
                return newNode(new TermNode(term));
            }
            if (match(TokenType.LPAREN)) {
                if (check(TokenType.RPAREN)) {
                    throw new BadRequestException("빈 괄호는 검색 조건으로 사용할 수 없습니다.");
                }
                Node node = parseOr();
                if (!match(TokenType.RPAREN)) {
                    throw new BadRequestException("검색식의 괄호가 닫히지 않았습니다.");
                }
                return node;
            }
            if (check(TokenType.EOF)) {
                throw new BadRequestException("검색식이 완성되지 않았습니다.");
            }
            throw new BadRequestException("검색식의 연산자 위치를 확인해 주세요.");
        }

        private Node newNode(Node node) {
            nodeCount++;
            if (nodeCount > MAX_NODE_COUNT) {
                throw new BadRequestException("검색 조건이 너무 많습니다. 핵심 단어 위주로 줄여 주세요.");
            }
            return node;
        }

        private boolean match(TokenType type) {
            if (!check(type)) {
                return false;
            }
            advance();
            return true;
        }

        private boolean check(TokenType type) {
            return peek().type == type;
        }

        private Token advance() {
            if (!isAtEnd()) {
                current++;
            }
            return previous();
        }

        private boolean isAtEnd() {
            return peek().type == TokenType.EOF;
        }

        private Token peek() {
            return tokens.get(current);
        }

        private Token previous() {
            return tokens.get(current - 1);
        }
    }
}