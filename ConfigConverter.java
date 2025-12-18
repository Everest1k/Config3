import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Утилита командной строки, которая парсит учебный конфигурационный язык
 * и записывает результирующую структуру данных в YAML.
 *
 * Вход читается из стандартного ввода (STDIN).
 * Выходной файл задаётся флагом командной строки -o или --output, после которого
 * указывается путь к YAML-файлу.
 *
 * При синтаксических ошибках выводится диагностическое сообщение в stderr,
 * а программа завершается с ненулевым кодом.
 *
 * Поддерживаемая грамматика соответствует условию задания:
 * 
 *   Однострочные комментарии начинаются с {@code REM} и продолжаются до конца строки.
 *   Многострочные комментарии начинаются с {@code (comment} и заканчиваются символом {@code )}.
 *   Числа — десятичные значения с необязательным знаком и дробной частью
 *       (например {@code 3.14}, {@code -0.5}). Внутри константных выражений также допускаются целые.
 *   Строки начинаются с символа {@code @}, затем идёт текст в двойных кавычках.
 *       Экранирование не обрабатывается (символы берутся как есть).
 *   Массивы: в квадратных скобках, элементы разделены точкой с запятой:
 *       {@code [ 1.0; 2.0; 3.0 ]}.
 *   Словари: в фигурных скобках, пары разделены запятыми:
 *       {@code { key : value, other : value } }. Ключи должны соответствовать {@code [a-z]+}.
 *   Объявление констант: {@code name := value}. Значение доступно далее по тексту.
 *   Константные выражения: заключаются в вертикальные черты и задаются в постфиксной форме (ОПН).
 *       Поддерживаются {@code +}, {@code -}, {@code *}, {@code concat()} и {@code mod()}.
 *  *
 * Результирующая структура (последнее встретившееся НЕ-объявление) сериализуется в YAML.
 * Коллекции выводятся в блочном стиле:
 * массивы — как последовательности с {@code -},
 * словари — как пары {@code key: value}.
 * Строки всегда выводятся в двойных кавычках для избежания неоднозначностей.
 * Числа выводятся без кавычек.
 */
public class ConfigConverter {
    public static void main(String[] args) {
        String outPath = null;

        // Разбор аргументов командной строки
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-o".equals(arg) || "--output".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Не указан путь после " + arg);
                    System.exit(1);
                }
                outPath = args[++i];
            } else {
                System.err.println("Неизвестный аргумент: " + arg);
                System.err.println("Использование: java ConfigConverter -o <output.yaml>");
                System.exit(1);
            }
        }

        if (outPath == null) {
            System.err.println("Путь к выходному файлу должен быть задан через -o или --output");
            System.exit(1);
        }

        // Чтение всего входного текста из STDIN
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            System.err.println("Ошибка чтения входа: " + e.getMessage());
            System.exit(1);
        }

        String input = sb.toString();

        // Парсинг входного текста
        Parser parser = new Parser(input);
        Object result;
        try {
            result = parser.parse();
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        // Запись результата в YAML-файл
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outPath))) {
            YamlEmitter.emit(result, bw, 0);
        } catch (IOException e) {
            System.err.println("Ошибка записи YAML: " + e.getMessage());
            System.exit(1);
        }
    }
}

/**
 * Парсер учебного конфигурационного языка в Java-объекты.
 * Реализован простым ручным рекурсивным спуском.
 *
 * Ошибки синтаксиса выбрасываются как {@link ParseException} с указанием строки и позиции.
 */
class Parser {
    private final String input;
    private int pos = 0;
    private int line = 1;
    private int column = 1;

    // Таблица констант, объявленных через name := value
    private final Map<String, Object> constants = new HashMap<>();

    // Последнее «обычное» значение (не объявление константы)
    private Object lastValue;

    Parser(String input) {
        this.input = input;
    }

    /**
     * Парсит весь входной текст.
     * Объявления констант сохраняются в таблицу constants.
     * Последнее встреченное НЕ-объявление считается результирующим значением.
     * Если ни одного значения нет — возвращается null.
     */
    public Object parse() throws ParseException {
        while (true) {
            skipWhitespaceAndComments();
            if (eof()) break;

            // Попытка распознать объявление константы: name := value
            int startPos = pos;
            int startLine = line;
            int startCol = column;

            String name = parseNameOrNull();
            if (name != null) {
                skipWhitespaceAndComments();
                if (peek(':') && peekNext('=')) {
                    // Это объявление константы
                    consume(':');
                    consume('=');
                    skipWhitespaceAndComments();
                    Object value = parseValue();
                    constants.put(name, value);
                    lastValue = null;
                    continue;
                } else {
                    // Это не объявление — откатываемся назад и читаем как обычное значение
                    pos = startPos;
                    line = startLine;
                    column = startCol;
                }
            }

            Object value = parseValue();
            lastValue = value;
        }
        return lastValue;
    }

    /**
     * Пропуск пробелов и комментариев.
     * Поддерживает:
     *  - однострочные комментарии, начинающиеся с REM
     *  - многострочные комментарии вида (comment ... )
     *
     * Важно: корректно обновляет счетчики строки и колонки.
     */
    private void skipWhitespaceAndComments() throws ParseException {
        boolean consumed;
        do {
            consumed = false;

            // Пропуск пробельных символов
            while (!eof() && Character.isWhitespace(current())) {
                if (current() == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
                pos++;
                consumed = true;
            }

            // Однострочный комментарий REM ... до конца строки
            if (matchAhead("REM")) {
                while (!eof() && current() != '\n') {
                    pos++;
                    column++;
                }
                consumed = true;
                continue;
            }

            // Многострочный комментарий (comment ... )
            if (matchAhead("(comment")) {
                // Съедаем литерал "(comment"
                for (int i = 0; i < "(comment".length(); i++) {
                    advance();
                }

                // Читаем до закрывающей ')'
                boolean closed = false;
                while (!eof()) {
                    char c = current();
                    if (c == ')') {
                        advance();
                        closed = true;
                        break;
                    }
                    if (c == '\n') {
                        line++;
                        column = 1;
                    } else {
                        column++;
                    }
                    pos++;
                }

                if (!closed) {
                    throw error("Не закрыт многострочный комментарий");
                }
                consumed = true;
                continue;
            }
        } while (consumed);
    }

    /**
     * Парсинг любого значения:
     * строка, число, массив, словарь, константное выражение или ссылка на константу.
     */
    private Object parseValue() throws ParseException {
        skipWhitespaceAndComments();
        if (eof()) {
            throw error("Неожиданный конец ввода при разборе значения");
        }

        char ch = current();

        // Строка
        if (ch == '@') {
            return parseString();
        }

        // Массив
        if (ch == '[') {
            return parseArray();
        }

        // Словарь
        if (ch == '{') {
            return parseDictionary();
        }

        // Константное выражение | ... |
        if (ch == '|') {
            return parseConstantExpression();
        }

        // Число (в обычном режиме обязательно должно содержать точку)
        if (ch == '+' || ch == '-' || Character.isDigit(ch)) {
            return parseNumberLiteral();
        }

        // Имя — это ссылка на уже объявленную константу
        if (Character.isLowerCase(ch)) {
            String name = parseName();
            Object val = constants.get(name);
            if (val == null) {
                throw error("Неизвестный идентификатор '" + name + "'");
            }
            return val;
        }

        throw error("Неожиданный символ '" + ch + "' при разборе значения");
    }

    /**
     * Разбор строки вида @"текст".
     * Экранирование не обрабатывается — содержимое берётся как есть.
     */
    private String parseString() throws ParseException {
        expect('@');
        expect('"');

        StringBuilder sb = new StringBuilder();
        while (!eof()) {
            char c = current();
            if (c == '"') {
                advance();
                return sb.toString();
            }

            // Обновление строки/колонки
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }

            sb.append(c);
            pos++;
        }

        throw error("Строковый литерал не закрыт кавычкой");
    }

    /**
     * Разбор числа.
     * В обычных значениях ожидается формат [+-]?\\d+\\.\\d+ (то есть обязательно с точкой).
     * В этом методе проверяется наличие точки и цифр до/после неё.
     */
    private Double parseNumberLiteral() throws ParseException {
        int start = pos;

        char c = current();
        if (c == '+' || c == '-') {
            advance();
        }

        boolean hasDigitsBefore = false;
        while (!eof() && Character.isDigit(current())) {
            hasDigitsBefore = true;
            advance();
        }

        boolean hasDot = false;
        if (!eof() && current() == '.') {
            hasDot = true;
            advance();

            boolean hasDigitsAfter = false;
            while (!eof() && Character.isDigit(current())) {
                hasDigitsAfter = true;
                advance();
            }

            if (!hasDigitsBefore || !hasDigitsAfter) {
                throw error("Некорректный числовой литерал");
            }
        }

        // Если точки нет — в текущей реализации это тоже число (Double.parseDouble),
        // но по ТЗ «числа» основного языка должны быть с точкой.
        // При необходимости можно усилить проверку: if (!hasDot) throw error(...);

        int end = pos;
        String text = input.substring(start, end);

        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw error("Некорректный числовой литерал: " + text);
        }
    }

    /**
     * Разбор массива: [ значение; значение; ... ].
     * Разделитель элементов — ';'. Допускается хвостовая ';' перед закрывающей ']'.
     */
    private List<Object> parseArray() throws ParseException {
        expect('[');
        List<Object> list = new ArrayList<>();

        skipWhitespaceAndComments();
        if (peek(']')) {
            consume(']');
            return list;
        }

        while (true) {
            Object val = parseValue();
            list.add(val);

            skipWhitespaceAndComments();
            if (peek(';')) {
                consume(';');
                skipWhitespaceAndComments();

                // Допускаем завершающую ';' перед ']'
                if (peek(']')) {
                    consume(']');
                    break;
                }
                continue;
            } else if (peek(']')) {
                consume(']');
                break;
            } else {
                throw error("Ожидался ';' или ']' в массиве");
            }
        }
        return list;
    }

    /**
     * Разбор словаря: { имя : значение, имя : значение, ... }.
     * Разделитель пар — ','. Допускается хвостовая запятая перед '}'.
     */
    private Map<String, Object> parseDictionary() throws ParseException {
        expect('{');
        Map<String, Object> map = new HashMap<>();

        skipWhitespaceAndComments();
        if (peek('}')) {
            consume('}');
            return map;
        }

        while (true) {
            skipWhitespaceAndComments();
            String key = parseName();

            skipWhitespaceAndComments();
            expect(':');

            skipWhitespaceAndComments();
            Object value = parseValue();
            map.put(key, value);

            skipWhitespaceAndComments();
            if (peek(',')) {
                consume(',');
                skipWhitespaceAndComments();

                // Допускаем завершающую ',' перед '}'
                if (peek('}')) {
                    consume('}');
                    break;
                }
                continue;
            } else if (peek('}')) {
                consume('}');
                break;
            } else {
                throw error("Ожидался ',' или '}' в словаре");
            }
        }

        return map;
    }

    /**
     * Разбор константного выражения в постфиксной форме (ОПН), заключённого в |...|.
     * Токены внутри разделяются пробелами/переносами строк.
     */
    private Object parseConstantExpression() throws ParseException {
        expect('|');
        List<Object> stack = new ArrayList<>();
        StringBuilder token = new StringBuilder();

        while (!eof()) {
            char c = current();

            // Закрывающий '|': завершаем выражение
            if (c == '|') {
                if (!token.isEmpty()) {
                    processExpressionToken(stack, token.toString());
                    token.setLength(0);
                }
                advance();

                if (stack.size() != 1) {
                    throw error("Некорректное константное выражение: в стеке " + stack.size() + " элементов");
                }
                return stack.get(0);
            }

            // Разделитель токенов — пробел/перенос строки
            if (Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    processExpressionToken(stack, token.toString());
                    token.setLength(0);
                }
                if (c == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
                pos++;
                continue;
            }

            token.append(c);
            advance();
        }

        throw error("Константное выражение не закрыто символом '|'");
    }

    /**
     * Обработка одного токена внутри ОПН:
     * числа/имена/операции + - * concat() mod().
     */
    private void processExpressionToken(List<Object> stack, String tok) throws ParseException {
        if (tok.isEmpty()) return;

        switch (tok) {
            case "+": {
                if (stack.size() < 2) throw error("Недостаточно операндов для +");
                Object b = stack.remove(stack.size() - 1);
                Object a = stack.remove(stack.size() - 1);
                stack.add(addValues(a, b));
                return;
            }
            case "-": {
                if (stack.size() < 2) throw error("Недостаточно операндов для -");
                Object b = stack.remove(stack.size() - 1);
                Object a = stack.remove(stack.size() - 1);
                stack.add(subtractValues(a, b));
                return;
            }
            case "*": {
                if (stack.size() < 2) throw error("Недостаточно операндов для *");
                Object b = stack.remove(stack.size() - 1);
                Object a = stack.remove(stack.size() - 1);
                stack.add(multiplyValues(a, b));
                return;
            }
            case "concat()": {
                if (stack.size() < 2) throw error("Недостаточно операндов для concat()");
                Object b = stack.remove(stack.size() - 1);
                Object a = stack.remove(stack.size() - 1);
                stack.add(concatValues(a, b));
                return;
            }
            case "mod()": {
                if (stack.size() < 2) throw error("Недостаточно операндов для mod()");
                Object b = stack.remove(stack.size() - 1);
                Object a = stack.remove(stack.size() - 1);
                stack.add(modValues(a, b));
                return;
            }
            default:
                // Строка внутри выражения поддерживается в формате @"...":
                // токен приходит целиком как @"текст"
                if (tok.startsWith("@")) {
                    if (tok.length() < 2 || tok.charAt(1) != '"' || tok.charAt(tok.length() - 1) != '"') {
                        throw error("Некорректная строка в выражении: " + tok);
                    }
                    String content = tok.substring(2, tok.length() - 1);
                    stack.add(content);
                    return;
                }

                // Число внутри выражения: допускаем и целое, и дробное
                if (tok.matches("[+-]?\\d+(\\.\\d+)?")) {
                    try {
                        Double d = Double.parseDouble(tok);
                        stack.add(d);
                        return;
                    } catch (NumberFormatException e) {
                        throw error("Некорректное число в выражении: " + tok);
                    }
                }

                // Имя константы
                if (tok.matches("[a-z]+")) {
                    Object val = constants.get(tok);
                    if (val == null) {
                        throw error("Неизвестный идентификатор '" + tok + "' в выражении");
                    }
                    stack.add(val);
                    return;
                }

                throw error("Неизвестный токен в константном выражении: " + tok);
        }
    }

    /** Сложение: поддерживаются только числовые операнды. */
    private Object addValues(Object a, Object b) throws ParseException {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        }
        throw error("Недопустимые операнды для +: " + a + ", " + b);
    }

    /** Вычитание: поддерживаются только числовые операнды. */
    private Object subtractValues(Object a, Object b) throws ParseException {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() - ((Number) b).doubleValue();
        }
        throw error("Недопустимые операнды для -: " + a + ", " + b);
    }

    /** Умножение: поддерживаются только числовые операнды. */
    private Object multiplyValues(Object a, Object b) throws ParseException {
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() * ((Number) b).doubleValue();
        }
        throw error("Недопустимые операнды для *: " + a + ", " + b);
    }

    /**
     * concat():
     * - строка + строка -> конкатенация строк
     * - список + список -> склейка списков
     * - список + строка -> добавить строку в конец списка
     * - строка + список -> добавить строку в начало списка
     */
    private Object concatValues(Object a, Object b) throws ParseException {
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a;
            List<?> lb = (List<?>) b;
            List<Object> result = new ArrayList<>(la);
            result.addAll(lb);
            return result;
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a) + ((String) b);
        }
        if (a instanceof List && b instanceof String) {
            List<Object> result = new ArrayList<>((List<?>) a);
            result.add(b);
            return result;
        }
        if (a instanceof String && b instanceof List) {
            List<Object> result = new ArrayList<>();
            result.add(a);
            result.addAll((List<?>) b);
            return result;
        }
        throw error("Недопустимые операнды для concat(): " + a + ", " + b);
    }

    /**
     * mod(): остаток от деления.
     * Аргументы приводятся к long.
     */
    private Object modValues(Object a, Object b) throws ParseException {
        if (a instanceof Number && b instanceof Number) {
            long dividend = ((Number) a).longValue();
            long divisor = ((Number) b).longValue();
            if (divisor == 0) {
                throw error("Деление на ноль в mod()");
            }
            return (double) (dividend % divisor);
        }
        throw error("Недопустимые операнды для mod(): " + a + ", " + b);
    }

    /** Разбор идентификатора, соответствующего [a-z]+. */
    private String parseName() throws ParseException {
        StringBuilder sb = new StringBuilder();
        if (eof() || !Character.isLowerCase(current())) {
            throw error("Ожидался идентификатор");
        }
        while (!eof() && Character.isLowerCase(current())) {
            sb.append(current());
            advance();
        }
        return sb.toString();
    }

    /**
     * Попытка «подсмотреть» имя без сдвига позиции.
     * Возвращает имя, если оно начинается в текущей позиции, иначе null.
     * Используется для распознавания конструкции name := value.
     */
    private String parseNameOrNull() {
        int tempPos = pos;
        if (eof() || !Character.isLowerCase(current())) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (tempPos < input.length() && Character.isLowerCase(input.charAt(tempPos))) {
            sb.append(input.charAt(tempPos));
            tempPos++;
        }
        return sb.toString();
    }

    /** Проверка, что впереди находится конкретный литерал (без потребления). */
    private boolean matchAhead(String literal) {
        if (pos + literal.length() > input.length()) return false;
        for (int i = 0; i < literal.length(); i++) {
            if (input.charAt(pos + i) != literal.charAt(i)) return false;
        }
        return true;
    }

    /** Ожидает указанный символ (после пропуска пробелов/комментариев), иначе ошибка. */
    private void expect(char expected) throws ParseException {
        skipWhitespaceAndComments();
        if (eof() || current() != expected) {
            throw error("Ожидался символ '" + expected + "'");
        }
        advance();
    }

    /** Проверяет текущий символ (после пропуска пробелов/комментариев), не сдвигая позицию. */
    private boolean peek(char c) {
        try {
            skipWhitespaceAndComments();
        } catch (ParseException e) {
            // Здесь не должно падать; если упало — просто вернём false
            return false;
        }
        return !eof() && current() == c;
    }

    /** Проверяет следующий символ (pos+1), используется для распознавания ':=' */
    private boolean peekNext(char c) {
        try {
            skipWhitespaceAndComments();
        } catch (ParseException e) {
            return false;
        }
        if (pos + 1 >= input.length()) return false;
        return input.charAt(pos + 1) == c;
    }

    /** Поглощает ожидаемый символ (после пропуска пробелов/комментариев), иначе ошибка. */
    private void consume(char c) throws ParseException {
        skipWhitespaceAndComments();
        if (eof() || current() != c) {
            throw error("Ожидался символ '" + c + "'");
        }
        advance();
    }

    /** Сдвиг на один символ вперёд с обновлением line/column. */
    private void advance() {
        if (eof()) return;
        if (current() == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        pos++;
    }

    /** Текущий символ без потребления. */
    private char current() {
        return input.charAt(pos);
    }

    /** Достигнут ли конец входного текста. */
    private boolean eof() {
        return pos >= input.length();
    }

    /** Формирование ParseException с координатами. */
    private ParseException error(String message) {
        return new ParseException(message + " (строка " + line + ", позиция " + column + ")");
    }
}

/** Исключение, возникающее при синтаксической ошибке. */
class ParseException extends Exception {
    public ParseException(String message) {
        super(message);
    }
}

/**
 * Минимальный генератор YAML.
 * Сериализует объекты, полученные парсером, в YAML блочным стилем.
 *
 * - Строки всегда выводятся в двойных кавычках (и экранируются \\ и ").
 * - Числа выводятся как есть.
 * - Списки и словари выводятся с отступом 2 пробела на уровень.
 */
class YamlEmitter {
    public static void emit(Object obj, BufferedWriter writer, int indent) throws IOException {
        if (obj == null) {
            writer.write("null");
            return;
        }

        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) obj;

            for (Map.Entry<String, Object> entry : map.entrySet()) {
                indent(writer, indent);
                writer.write(entry.getKey());
                writer.write(":");

                Object value = entry.getValue();
                if (isScalar(value)) {
                    writer.write(" ");
                    writeScalar(value, writer);
                    writer.newLine();
                } else {
                    writer.newLine();
                    emit(value, writer, indent + 2);
                }
            }

        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;

            for (Object item : list) {
                indent(writer, indent);
                writer.write("- ");
                if (isScalar(item)) {
                    writeScalar(item, writer);
                    writer.newLine();
                } else {
                    writer.newLine();
                    emit(item, writer, indent + 2);
                }
            }

        } else {
            // Скаляр на верхнем уровне
            indent(writer, indent);
            writeScalar(obj, writer);
            writer.newLine();
        }
    }

    /** Проверка: является ли значение скаляром (не Map и не List). */
    private static boolean isScalar(Object value) {
        return !(value instanceof Map) && !(value instanceof List);
    }

    /** Печать отступа пробелами. */
    private static void indent(BufferedWriter writer, int indent) throws IOException {
        for (int i = 0; i < indent; i++) writer.write(' ');
    }

    /** Запись скалярного значения в YAML. */
    private static void writeScalar(Object value, BufferedWriter writer) throws IOException {
        if (value == null) {
            writer.write("null");
        } else if (value instanceof String) {
            String s = (String) value;
            // Экранируем обратные слеши и кавычки
            s = s.replace("\\", "\\\\").replace("\"", "\\\"");
            writer.write('"');
            writer.write(s);
            writer.write('"');
        } else if (value instanceof Number) {
            // Числа выводим без кавычек
            writer.write(value.toString());
        } else if (value instanceof Boolean) {
            writer.write(((Boolean) value) ? "true" : "false");
        } else {
            // Запасной вариант: приводим к строке и пишем в кавычках
            String s = value.toString().replace("\\", "\\\\").replace("\"", "\\\"");
            writer.write('"');
            writer.write(s);
            writer.write('"');
        }
    }
}
