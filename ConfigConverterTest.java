import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Простой набор тестов для ConfigConverter и Parser.
 *
 * Используются базовые проверки через assert, чтобы убедиться, что:
 * 1) значения корректно разбираются парсером;
 * 2) сериализация в YAML работает ожидаемо.
 *
 * Каждый тест печатает своё имя и статус (PASSED/FAILED).
 * Если хотя бы один тест падает — процесс завершается с ненулевым кодом.
 */
public class ConfigConverterTest {
    public static void main(String[] args) throws Exception {
        List<TestCase> tests = new ArrayList<>();
        tests.add(new TestCase("testNumber", ConfigConverterTest::testNumber));
        tests.add(new TestCase("testString", ConfigConverterTest::testString));
        tests.add(new TestCase("testArray", ConfigConverterTest::testArray));
        tests.add(new TestCase("testDictionary", ConfigConverterTest::testDictionary));
        tests.add(new TestCase("testNested", ConfigConverterTest::testNested));
        tests.add(new TestCase("testConstants", ConfigConverterTest::testConstants));
        tests.add(new TestCase("testConcat", ConfigConverterTest::testConcat));
        tests.add(new TestCase("testMod", ConfigConverterTest::testMod));
        tests.add(new TestCase("testSyntaxError", ConfigConverterTest::testSyntaxError));

        boolean passedAll = true;

        // Последовательно запускаем все тесты
        for (TestCase tc : tests) {
            try {
                tc.runnable.run();
                System.out.println(tc.name + ": PASSED");
            } catch (AssertionError | Exception e) {
                passedAll = false;
                System.err.println(tc.name + ": FAILED");
                e.printStackTrace();
            }
        }

        // Если был хотя бы один провал — завершаем с кодом ошибки
        if (!passedAll) {
            System.exit(1);
        }
    }

    /** Тест разбора числа и вывода в YAML. */
    private static void testNumber() throws Exception {
        String input = "1.0\n";
        Object obj = new Parser(input).parse();
        assert obj instanceof Double && ((Double) obj) == 1.0;

        String yaml = toYaml(obj);
        assert yaml.trim().equals("1.0");
    }

    /** Тест разбора строки и вывода в YAML. */
    private static void testString() throws Exception {
        String input = "@\"Hello\"\n";
        Object obj = new Parser(input).parse();
        assert obj instanceof String && obj.equals("Hello");

        String yaml = toYaml(obj).trim();
        assert yaml.equals("\"Hello\"");
    }

    /** Тест разбора массива и вывода в YAML-список. */
    private static void testArray() throws Exception {
        String input = "[ 1.0; 2.0; 3.0 ]\n";
        Object obj = new Parser(input).parse();
        assert obj instanceof List;

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) obj;
        assert list.size() == 3;

        String yaml = toYaml(obj);
        String expected = "- 1.0\n- 2.0\n- 3.0\n";
        assert yaml.equals(expected);
    }

    /** Тест разбора словаря и вывода в YAML-отображение (map). */
    private static void testDictionary() throws Exception {
        String input = "{ a : 1.0, b : 2.0 }\n";
        Object obj = new Parser(input).parse();
        assert obj instanceof Map;

        String yaml = toYaml(obj);

        // Порядок ключей в YAML может отличаться (Map не гарантирует порядок),
        // поэтому допускаем оба варианта.
        String expected1 = "a: 1.0\nb: 2.0\n";
        String expected2 = "b: 2.0\na: 1.0\n";
        assert yaml.equals(expected1) || yaml.equals(expected2);
    }

    /**
     * Тест вложенных структур (словарь + массив словарей).
     * Здесь не сравниваем YAML полностью, а делаем базовые проверки,
     * что структура вообще присутствует.
     */
    private static void testNested() throws Exception {
        String input = "{ items : [ { id : 1.0, name : @\"A\" }; { id : 2.0, name : @\"B\" } ] }\n";
        Object obj = new Parser(input).parse();

        String yaml = toYaml(obj);

        // Простейшая проверка: в YAML должны встретиться маркеры списка и словаря
        assert yaml.contains("-");
        assert yaml.contains(":");
    }

    /** Тест объявления констант и вычисления выражения |x y +|. */
    private static void testConstants() throws Exception {
        String input = "x := 1.0\ny := 2.0\n{ sum : |x y +| }\n";
        Object obj = new Parser(input).parse();
        assert obj instanceof Map;

        String yaml = toYaml(obj);
        assert yaml.contains("sum: 3.0");
    }

    /** Тест concat(): объединение списка и добавление строки. */
    private static void testConcat() throws Exception {
        String input = "a := [ @\"x\"; @\"y\" ]\n{ merged : |a @\"z\" concat()| }\n";
        Object obj = new Parser(input).parse();

        String yaml = toYaml(obj);

        // Ожидаем, что merged содержит три элемента: x, y, z
        assert yaml.contains("- \"x\"");
        assert yaml.contains("- \"y\"");
        assert yaml.contains("- \"z\"");
    }

    /** Тест mod(): |5 2 mod()| -> 1.0 */
    private static void testMod() throws Exception {
        String input = "{ r : |5 2 mod()| }\n";
        Object obj = new Parser(input).parse();

        String yaml = toYaml(obj);
        assert yaml.contains("r: 1.0");
    }

    /** Тест синтаксической ошибки: отсутствует закрывающая ']' у массива. */
    private static void testSyntaxError() throws Exception {
        String input = "[ 1.0; 2.0\n"; // нет закрывающей скобки
        try {
            new Parser(input).parse();
            assert false : "Ожидалась ошибка парсинга";
        } catch (ParseException e) {
            // Ошибка ожидаема — тест считается успешным
        }
    }

    /** Вспомогательный метод: сериализовать объект в YAML-строку. */
    private static String toYaml(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (BufferedWriter bw = new BufferedWriter(sw)) {
            YamlEmitter.emit(obj, bw, 0);
        }
        return sw.toString();
    }

    /** Простой контейнер для тест-кейса (имя + функция запуска). */
    private static class TestCase {
        final String name;
        final ThrowingRunnable runnable;

        TestCase(String name, ThrowingRunnable runnable) {
            this.name = name;
            this.runnable = runnable;
        }
    }

    /** Функциональный интерфейс: тест, который может бросать исключение. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}