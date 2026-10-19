package com.ramussoft.local.ai;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Element;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.AccessRules;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Row;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Stream;
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.data.AbstractRow;
import com.ramussoft.pb.idef.elements.Ordinate;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.elements.Point;
import com.ramussoft.pb.idef.elements.SectorRefactor;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.idef.visual.WayStroke;
import com.dsoft.pb.types.FRectangle;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Stroke;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.core.attribute.standard.StandardAttributesPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Allow-listed write tools for the AI. It uses the normal Ramus Engine, so
 * journal/undo integration remains in the application. No raw YAML or shell
 * operation is exposed here.
 */
public final class RamusToolExecutor {
    public List<Element> execute(Engine engine, AccessRules rules, AiPlan plan) {
        return execute(engine, rules, plan, message -> { });
    }

    public List<Element> execute(Engine engine, AccessRules rules, AiPlan plan, Consumer<String> progress) {
        List<Element> created = new ArrayList<>();
        java.util.Map<String, Row> functions = new java.util.HashMap<>();
        Qualifier functionQualifier = engine.getQualifierByName("Работы");
        if (functionQualifier == null)
            throw new IllegalStateException("В проекте нет IDEF0-классификатора «Работы».");
        if (!IDEF0Plugin.isFunction(functionQualifier))
            IDEF0Plugin.installFunctionAttributes(functionQualifier, engine);
        Attribute functionName = StandardAttributesPlugin.getAttributeNameAttribute(engine);
        boolean hasNameAttribute = functionQualifier.getAttributes().stream()
                .anyMatch(attribute -> attribute.getId() == functionName.getId());
        if (!hasNameAttribute) functionQualifier.getAttributes().add(functionName);
        functionQualifier.setAttributeForName(functionName.getId());
        engine.updateQualifier(functionQualifier);
        // A contextual DataPlugin is essential: the raw NDataPlugin has no
        // base function and cannot own visible IDEF0 boxes or sectors.
        DataPlugin data = NDataPluginFactory.getDataPlugin(functionQualifier, engine, rules);
        // The qualifier may have been cached before its Name attribute was
        // installed. Rebuild row sets so Function.setName uses the same
        // attribute mapping as the editor.
        data.clear();
        Function diagram = (Function) data.getBaseFunction();
        if (diagram == null)
            throw new IllegalStateException("Не удалось получить корневую функцию IDEF0-модели.");
        diagram.setName("Модель транспортной компании");
        java.util.Map<String, Row> functionsByPath = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Map<String, Row>> functionsByDiagram = new java.util.LinkedHashMap<>();
        progress.accept("Шаг 1/4 — создаю контекстную диаграмму A-0");
        // A-0 contains one context function. The assignment's operational
        // activities belong to A0 and the second-stage breakdown to A2.
        for (AiPlan.AiOperation operation : plan.operations()) {
            if (!"create_element".equals(operation.tool())) continue;
            if (!"Работы".equals(operation.qualifier())) continue;
            Row row = findChildByName(data, diagram, operation.name());
            if (row == null) {
                row = data.createFunction(diagram, Function.TYPE_PROCESS);
                if (row == null) throw new IllegalStateException("Ramus не создал контекстный блок: " + operation.name());
                row.setName(operation.name());
                created.add(row.getElement());
            }
            if (!(row instanceof Function)) throw new IllegalStateException("Не найден контекстный блок: " + operation.name());
            layout((Function) row, 0);
            functionsByPath.put(operation.name(), row);
            functionsByDiagram.computeIfAbsent("", key -> new java.util.LinkedHashMap<>()).put(operation.name(), row);
        }
        // Never delete older diagrams based solely on their names: those may
        // contain the user's own work. Rebuild in a fresh project if needed.
        progress.accept("Шаг 2/4 — размещаю работы на диаграммах");
        // Parent paths use the exact visible hierarchy, e.g. Context::A2.
        for (AiPlan.AiOperation operation : plan.operations()) {
            if (!"create_element".equals(operation.tool()) || !operation.qualifier().startsWith("Работы::")) continue;
            String parentPath = operation.qualifier().substring("Работы::".length());
            Row parent = functionsByPath.get(parentPath);
            if (!(parent instanceof Function)) throw new IllegalArgumentException("Не найдена родительская работа: " + parentPath);
            Row row = findChildByName(data, (Function) parent, operation.name());
            if (row == null) {
                row = data.createFunction((Function) parent, Function.TYPE_PROCESS);
                if (row == null) throw new IllegalStateException("Ramus не создал дочерний блок: " + operation.name());
                row.setName(operation.name());
                created.add(row.getElement());
            }
            java.util.Map<String, Row> local = functionsByDiagram.computeIfAbsent(parentPath, key -> new java.util.LinkedHashMap<>());
            int childIndex = local.size();
            String path = parentPath + "::" + operation.name();
            functionsByPath.put(path, row);
            local.put(operation.name(), row);
        }
        // Reflow each complete level only after all of its siblings exist.
        // This keeps the same plan readable when the number of blocks changes.
        for (java.util.Map.Entry<String, java.util.Map<String, Row>> entry : functionsByDiagram.entrySet()) {
            String parentPath = entry.getKey();
            if (parentPath.isEmpty()) continue;
            List<Function> siblings = new ArrayList<>();
            for (Row row : entry.getValue().values())
                if (row instanceof Function) siblings.add((Function) row);
            int longestCaption = siblings.stream().mapToInt(row -> row.getName().length()).max().orElse(0);
            for (int i = 0; i < siblings.size(); i++) {
                if (parentPath.contains("::") || siblings.size() > 3)
                    layoutChild(siblings.get(i), i, siblings.size(), longestCaption);
                else layoutStage(siblings.get(i), i, siblings.size(), longestCaption);
            }
        }
        // DeepSeek may return operations in arbitrary order. Resolve all
        // function names first, then build streams from the completed map.
        for (AiPlan.AiOperation operation : plan.operations()) {
            if (!"create_stream".equals(operation.tool())) continue;
            if (!"Потоки".equals(operation.qualifier()) && !operation.qualifier().startsWith("Потоки::"))
                throw new IllegalArgumentException("Неизвестная область стрелок: " + operation.qualifier());
        }
        java.util.Map<String, java.util.List<AiPlan.AiOperation>> streamsByParent = new java.util.LinkedHashMap<>();
        for (AiPlan.AiOperation operation : plan.operations()) {
            if (!"create_stream".equals(operation.tool())) continue;
            String parentPath = operation.qualifier().equals("Потоки") ? "" : operation.qualifier().substring("Потоки::".length());
            streamsByParent.computeIfAbsent(parentPath, key -> new ArrayList<>()).add(operation);
        }
        progress.accept("Шаг 3/4 — прокладываю стрелки по уровням диаграммы");
        for (java.util.Map.Entry<String, java.util.List<AiPlan.AiOperation>> entry : streamsByParent.entrySet()) {
            String parentPath = entry.getKey();
            Function activeDiagram = parentPath.isEmpty() ? diagram : (Function) functionsByPath.get(parentPath);
            java.util.Map<String, Row> localFunctions = functionsByDiagram.get(parentPath);
            if (activeDiagram == null || localFunctions == null) throw new IllegalArgumentException("Не найдена декомпозиция: " + parentPath);
            MovingArea area = new MovingArea(data, activeDiagram);
            area.setActiveFunction(activeDiagram);
            // Replace only sectors actually belonging to this diagram, never
            // all same-named streams elsewhere in the project.
            java.util.Set<String> namesToReplace = new java.util.HashSet<>();
            for (AiPlan.AiOperation operation : entry.getValue()) namesToReplace.add(operation.name());
            List<Sector> sectorsToReplace = new ArrayList<>();
            for (int i = 0; i < area.getRefactor().getSectorsCount(); i++) {
                Sector existing = area.getRefactor().getSector(i).getSector();
                if (existing.getStream() != null && namesToReplace.contains(existing.getStream().getName()))
                    sectorsToReplace.add(existing);
            }
            for (Sector existing : sectorsToReplace) existing.remove();
            if (!sectorsToReplace.isEmpty()) {
                area = new MovingArea(data, activeDiagram);
                area.setActiveFunction(activeDiagram);
            }
            progress.accept("Обрабатываю уровень: " + (parentPath.isEmpty() ? "A-0" : parentPath.replace("::", " → ")));
            java.util.IdentityHashMap<Function, java.util.Map<Integer, Integer>> portUse = new java.util.IdentityHashMap<>();
            List<FRectangle> labelBounds = new ArrayList<>();
            List<FRectangle> blockBounds = new ArrayList<>();
            for (Row row : localFunctions.values())
                if (row instanceof Function) blockBounds.add(((Function) row).getBounds());
            java.util.Set<String> labeledStreams = new java.util.HashSet<>();
            int routeIndex = 0;
            for (AiPlan.AiOperation operation : entry.getValue()) {
                if (operation.source().isBlank() || operation.target().isBlank()) throw new IllegalArgumentException("У стрелки не заданы начало и конец: " + operation.name());
                Stream stream = findStream(data, operation.name());
                if (stream == null) {
                    stream = (Stream) data.createRow(data.getBaseStream(), true);
                    stream.setName(operation.name());
                    created.add(stream.getElement());
                }
                if (operation.source().startsWith("@")) {
                    Row target = resolveFunction(localFunctions, operation.target());
                    if (!(target instanceof Function)) throw new IllegalArgumentException("Не найдена работа-получатель стрелки: " + operation.target());
                    int side = boundarySide(operation.source());
                    createBoundaryArrow(area, (Function) target, stream, true, side,
                            nextPort(portUse, (Function) target, side), routeIndex++, labelBounds, blockBounds, labeledStreams,
                            operation.style(), operation.color());
                } else if (operation.target().startsWith("@")) {
                    Row source = resolveFunction(localFunctions, operation.source());
                    if (!(source instanceof Function)) throw new IllegalArgumentException("Не найдена работа-источник стрелки: " + operation.source());
                    int side = boundarySide(operation.target());
                    createBoundaryArrow(area, (Function) source, stream, false, side,
                            nextPort(portUse, (Function) source, side), routeIndex++, labelBounds, blockBounds, labeledStreams,
                            operation.style(), operation.color());
                } else {
                    Row source = resolveFunction(localFunctions, operation.source()), target = resolveFunction(localFunctions, operation.target());
                    if (!(source instanceof Function) || !(target instanceof Function)) throw new IllegalArgumentException("Стрелка ссылается на неизвестную работу: " + operation.source() + " -> " + operation.target());
                    Function from = (Function) source, to = (Function) target;
                    int sourcePort = nextPort(portUse, from, com.ramussoft.pb.idef.visual.MovingPanel.RIGHT);
                    int targetSide = "control".equalsIgnoreCase(operation.icom())
                            ? com.ramussoft.pb.idef.visual.MovingPanel.TOP
                            : "mechanism".equalsIgnoreCase(operation.icom())
                            ? com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM
                            : com.ramussoft.pb.idef.visual.MovingPanel.LEFT;
                    int targetPort = nextPort(portUse, to, targetSide);
                    createVisibleArrow(area, from, to, stream, operation.icom(), sourcePort, targetPort,
                            routeIndex++, labelBounds, blockBounds, labeledStreams, operation.style(), operation.color());
                }
            }
            // Reconsider labels after every line exists, including the native
            // boundary stubs inherited from the context diagram.
            List<FRectangle> lineBounds = new ArrayList<>();
            for (int i = 0; i < area.getRefactor().getSectorsCount(); i++) {
                Point[] path = area.getRefactor().getSector(i).getPoints();
                for (int j = 1; j < path.length; j++) {
                    Point a = path[j - 1], b = path[j];
                    lineBounds.add(new FRectangle(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                            Math.max(1, Math.abs(a.getX() - b.getX())), Math.max(1, Math.abs(a.getY() - b.getY()))));
                }
            }
            labelBounds.clear(); labeledStreams.clear();
            List<PaintSector> arrowsToLabel = new ArrayList<>();
            for (int i = 0; i < area.getRefactor().getSectorsCount(); i++) {
                PaintSector arrow = area.getRefactor().getSector(i);
                if (arrow.getStream() != null && arrow.getText() != null)
                    arrowsToLabel.add(arrow);
            }
            // Place the widest labels first, when the largest gaps are still free.
            arrowsToLabel.sort((a, b) -> Integer.compare(b.getStream().getName().length(), a.getStream().getName().length()));
            for (PaintSector arrow : arrowsToLabel)
                if (arrow.getText() != null)
                    positionLabel(arrow, labelBounds, blockBounds, lineBounds, labeledStreams, arrow.getStream().getName());
            area.getRefactor().saveToFunction();
            MovingArea persisted = new MovingArea(data, activeDiagram);
            persisted.setActiveFunction(activeDiagram);
            if (persisted.getRefactor().getSectorsCount() < entry.getValue().size())
                throw new IllegalStateException("Ramus сохранил не все стрелки диаграммы «"
                        + (parentPath.isEmpty() ? "A-0" : parentPath.replace("::", " → "))
                        + "»: ожидалось " + entry.getValue().size() + ", после перечитывания найдено "
                        + persisted.getRefactor().getSectorsCount());
        }
        progress.accept("Шаг 4/4 — проверяю названия и сохраняю результат");
        // Do not report a successful run if the model API silently failed to
        // persist the labels or the drawable IDEF0 geometry.
        for (AiPlan.AiOperation operation : plan.operations()) {
            if (!"create_element".equals(operation.tool())) continue;
            Row function;
            String path = "Работы".equals(operation.qualifier()) ? operation.name()
                    : operation.qualifier().startsWith("Работы::")
                    ? operation.qualifier().substring("Работы::".length()) + "::" + operation.name() : null;
            if (path == null) continue;
            function = functionsByPath.get(path);
            if (function == null || !operation.name().equals(function.getName()))
                throw new IllegalStateException("Ramus не сохранил название блока: " + operation.name());
        }
        return created;
    }

    private static Row findChildByName(DataPlugin data, Function parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Row child = (Row) parent.getChildAt(i);
            if (child instanceof Function && name.equals(child.getName())) return child;
        }
        return null;
    }

    /** Remove only the exact child blocks/arrows created by the previous AI draft,
     * so regenerating the corrected lab structure does not leave duplicate diagrams. */
    private static void cleanupPreviousTransportDetails(DataPlugin data, Function baseDiagram) {
        java.util.Set<String> oldFunctions = java.util.Set.of(
                "Заключить договор с клиентом", "Подготовить груз, предоставить транспорт и организовать логистику", "Доставить груз по адресу в срок",
                "Уточнить характеристики груза, количество, адрес и срок", "Согласовать тариф и условия перевозки",
                "Зафиксировать заказ охраны или сопровождения экспедитора", "Оформить договор и транспортные документы",
                "Принять груз клиента и сверить его с договором", "Подготовить и упаковать груз при необходимости",
                "Организовать хранение до согласованной отгрузки при необходимости", "Рассчитать маршрут и график перевозки",
                "Подобрать транспорт и подтвердить техническую готовность", "Погрузить груз и оформить отправку",
                "Выпустить груз на маршрут и начать перевозку", "Контролировать местоположение и соблюдение срока доставки",
                "Обеспечить охрану и сопровождение, если заказаны", "Доставить и разгрузить груз по указанному адресу",
                "Получить подтверждение доставки и закрыть документы",
                "Принять груз клиента и проверить договорные данные", "Оформить документы и согласовать тарифы",
                "Подготовить груз к перевозке", "Хранить груз при необходимости",
                "Спланировать логистику и маршрут", "Обеспечить техническую готовность транспорта",
                "Организовать охрану и сопровождение экспедитора",
                "Контролировать перемещение и срок доставки",
                "Принять груз клиента и сверить с договором",
                "Подготовить груз и организовать хранение при необходимости",
                "Спланировать логистику и предоставить исправный транспорт");
        java.util.Set<String> oldStreams = java.util.Set.of(
                "Заявка клиента и передаваемый груз", "Правила перевозки, тарифные условия и срок",
                "Персонал, транспорт, склад и погрузочная техника", "Параметры груза и доставки",
                "Согласованный тариф и выбранные условия", "Условия договора и заказ дополнительных услуг",
                "Запрос клиента и сведения о грузе", "Правила перевозки и тарифные ограничения",
                "Менеджер и система оформления документов", "Подписанный договор и согласованные услуги",
                "Принятый и проверенный груз", "Подготовленный груз к размещению", "Груз, готовый к отгрузке",
                "Маршрут, ограничения и временной график", "Исправное подходящее транспортное средство",
                "Договор и груз, переданный клиентом", "Срок доставки и условия сохранности груза",
                "Водители, логисты, склад и транспортный парк", "Подготовленный и погруженный груз, маршрут и документы",
                "Груз и документы, выпущенные на маршрут", "Местоположение и соблюдение срока доставки",
                "Охрана и экспедиторское сопровождение", "Доставленный и разгруженный груз",
                "Подготовленный груз, маршрут и отправочные документы", "Адрес, срок, требования к сохранности и договор",
                "Водитель, исправное транспортное средство, экспедитор и охрана",
                "Принятый клиентом груз и закрывающие документы",
                "Договорные данные для оформления", "Оформленные документы и согласованные тарифы",
                "Данные клиента и принятый груз", "Подготовленный груз", "Груз, подготовленный к отправке",
                "Маршрут и график перевозки", "Исправный транспорт", "Транспорт для выполнения перевозки",
                "Загруженный груз", "Условие охраны и сопровождения по договору",
                "Охрана и сопровождение груза", "Сведения о ходе доставки",
                "Отклонения маршрута и корректирующие действия",
                "Договор, параметры груза, адрес и срок", "Груз, переданный клиентом",
                "План перевозки и подготовленный груз", "Условия доставки, охраны и сопровождения",
                "Подтверждение доставки и закрывающие документы", "Сведения о движении груза и отклонениях",
                "Правила перевозки, тарифы и сроки", "Транспорт, персонал, склад и средства погрузки",
                "Принятый груз и сведения договора", "Подготовленный груз к погрузке",
                "Маршрут, график и подходящее транспортное средство",
                "Исправный транспорт для выполнения перевозки",
                "Договор, характеристики груза и пункт доставки",
                "Правила перевозки, тарифы и требования к сохранности",
                "Транспортный парк, водитель, склад и погрузочная техника",
                "Груз и план перевозки, готовые к исполнению договора");
        for (Sector sector : data.getAllSectors())
            if (sector.getStream() != null && oldStreams.contains(sector.getStream().getName())) sector.remove();
        for (int i = baseDiagram.getChildCount() - 1; i >= 0; i--) {
            Row child = (Row) baseDiagram.getChildAt(i);
            if (child instanceof Function && oldFunctions.contains(child.getName())) ((AbstractRow) child).remove();
        }
        for (String parentName : List.of("Заключить договор с клиентом",
                "Подготовить груз, предоставить транспорт и организовать логистику",
                "Доставить груз по адресу в срок")) {
            Row oldParent = findChildByName(data, baseDiagram, parentName);
            if (!(oldParent instanceof Function)) continue;
            Function function = (Function) oldParent;
            for (int i = function.getChildCount() - 1; i >= 0; i--) {
                Row child = (Row) function.getChildAt(i);
                if (child instanceof Function && oldFunctions.contains(child.getName())) ((AbstractRow) child).remove();
            }
            ((AbstractRow) oldParent).remove();
        }
        for (Object value : data.getChilds(data.getBaseStream(), true))
            if (value instanceof Stream && oldStreams.contains(((Stream) value).getName())) ((AbstractRow) value).remove();
    }

    private static Stream findStream(DataPlugin data, String name) {
        for (Object value : data.getChilds(data.getBaseStream(), true))
            if (value instanceof Stream && name.equals(((Stream) value).getName())) return (Stream) value;
        return null;
    }

    private static void createVisibleArrow(MovingArea area, Function source, Function target, Stream stream,
                                           String icom, int sourcePort, int targetPort, int routeIndex,
                                           List<FRectangle> labelBounds, List<FRectangle> blockBounds,
                                           java.util.Set<String> labeledStreams,
                                           String style, String color) {
        FRectangle from = source.getBounds();
        FRectangle to = target.getBounds();
        int targetSide = "control".equalsIgnoreCase(icom)
                ? com.ramussoft.pb.idef.visual.MovingPanel.TOP
                : "mechanism".equalsIgnoreCase(icom)
                ? com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM
                : com.ramussoft.pb.idef.visual.MovingPanel.LEFT;
        double targetX = targetSide == com.ramussoft.pb.idef.visual.MovingPanel.LEFT ? to.getLeft()
                : targetSide == com.ramussoft.pb.idef.visual.MovingPanel.TOP || targetSide == com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM
                ? clamp(to.getLeft() + to.getWidth() / 2 + portOffset(targetPort), to.getLeft() + 20, to.getRight() - 20) : to.getRight();
        double targetY = targetSide == com.ramussoft.pb.idef.visual.MovingPanel.TOP ? to.getTop()
                : targetSide == com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM ? to.getBottom()
                : clamp(to.getTop() + to.getHeight() / 2 + portOffset(targetPort), to.getTop() + 15, to.getBottom() - 15);
        // In IDEF0 every internal output leaves the right side, including
        // feedback to a block positioned above or to the left.
        int sourceSide = com.ramussoft.pb.idef.visual.MovingPanel.RIGHT;
        double sourceX = sourceSide == com.ramussoft.pb.idef.visual.MovingPanel.RIGHT ? from.getRight()
                : sourceSide == com.ramussoft.pb.idef.visual.MovingPanel.LEFT ? from.getLeft()
                : clamp(from.getLeft() + from.getWidth() / 2 + portOffset(sourcePort), from.getLeft() + 20, from.getRight() - 20);
        double sourceY = sourceSide == com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM ? from.getBottom()
                : sourceSide == com.ramussoft.pb.idef.visual.MovingPanel.TOP ? from.getTop()
                : clamp(from.getTop() + from.getHeight() / 2 + portOffset(sourcePort), from.getTop() + 15, from.getBottom() - 15);
        createArrow(area,
                functionPoint(source, sourceX, sourceY, sourceSide, SectorRefactor.TYPE_START),
                functionPoint(target, targetX, targetY, targetSide, SectorRefactor.TYPE_END),
                new Row[]{source, target}, stream, routeIndex, icom, labelBounds, blockBounds, labeledStreams, style, color);
    }

    private static void createBoundaryArrow(MovingArea area, Function function, Stream stream, boolean incoming,
                                            int side, int port, int routeIndex, List<FRectangle> labelPositions,
                                            List<FRectangle> blockBounds, java.util.Set<String> labeledStreams,
                                            String style, String color) {
        FRectangle bounds = function.getBounds();
        double centerX = clamp(bounds.getLeft() + bounds.getWidth() / 2 + portOffset(port), bounds.getLeft() + 20, bounds.getRight() - 20);
        double centerY = clamp(bounds.getTop() + bounds.getHeight() / 2 + portOffset(port), bounds.getTop() + 15, bounds.getBottom() - 15);
        SectorRefactor.PerspectivePoint functionPoint;
        switch (side) {
            case com.ramussoft.pb.idef.visual.MovingPanel.LEFT -> functionPoint = functionPoint(function, bounds.getLeft(), centerY, side, incoming ? SectorRefactor.TYPE_END : SectorRefactor.TYPE_START);
            case com.ramussoft.pb.idef.visual.MovingPanel.RIGHT -> functionPoint = functionPoint(function, bounds.getRight(), centerY, side, incoming ? SectorRefactor.TYPE_END : SectorRefactor.TYPE_START);
            case com.ramussoft.pb.idef.visual.MovingPanel.TOP -> functionPoint = functionPoint(function, centerX, bounds.getTop(), side, incoming ? SectorRefactor.TYPE_END : SectorRefactor.TYPE_START);
            case com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM -> functionPoint = functionPoint(function, centerX, bounds.getBottom(), side, incoming ? SectorRefactor.TYPE_END : SectorRefactor.TYPE_START);
            default -> throw new IllegalArgumentException("Неизвестная сторона IDEF0: " + side);
        }
        double borderX = side == com.ramussoft.pb.idef.visual.MovingPanel.TOP || side == com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM ? centerX
                : side == com.ramussoft.pb.idef.visual.MovingPanel.RIGHT ? area.CLIENT_WIDTH - 10 - portOffset(port) : 10 + portOffset(port);
        double borderY = side == com.ramussoft.pb.idef.visual.MovingPanel.LEFT || side == com.ramussoft.pb.idef.visual.MovingPanel.RIGHT
                ? centerY : (side == com.ramussoft.pb.idef.visual.MovingPanel.TOP ? 10 + portOffset(port) : area.CLIENT_HEIGHT - 40 - portOffset(port));
        String boundaryIcom = switch (side) {
            case com.ramussoft.pb.idef.visual.MovingPanel.LEFT -> "input";
            case com.ramussoft.pb.idef.visual.MovingPanel.TOP -> "control";
            case com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM -> "mechanism";
            default -> "output";
        };
        createArrow(area,
                incoming ? boundaryPoint(borderX, borderY, side, SectorRefactor.TYPE_START) : functionPoint,
                incoming ? functionPoint : boundaryPoint(borderX, borderY, side, SectorRefactor.TYPE_END),
                new Row[]{function}, stream, routeIndex, boundaryIcom, labelPositions, blockBounds, labeledStreams, style, color);
    }

    private static void createArrow(MovingArea area, SectorRefactor.PerspectivePoint start,
                                    SectorRefactor.PerspectivePoint end, Row[] rows, Stream stream, int routeIndex,
                                    String icom, List<FRectangle> labelBounds, List<FRectangle> blockBounds,
                                    java.util.Set<String> labeledStreams,
                                    String style, String requestedColor) {
        int previousSectorCount = area.getRefactor().getSectorsCount();
        area.setState(MovingArea.START_POINT_ADDING);
        area.getRefactor().setPoint(start);
        area.doSector();
        area.getRefactor().setPoint(end);
        area.doSector();
        SectorRefactor refactor = area.getRefactor();
        if (refactor.getSectorsCount() != previousSectorCount + 1)
            throw new IllegalStateException("Ramus не создал геометрию стрелки: " + stream.getName());
        PaintSector paintSector = refactor.getSector(refactor.getSectorsCount() - 1);
        paintSector.setStream(stream, com.ramussoft.pb.idef.elements.ReplaceStreamType.SIMPLE);
        paintSector.setShowText(true);
        paintSector.createTexts();
        paintSector.setRows(rows);
        paintSector.setColor(arrowColor(icom, requestedColor));
        paintSector.setFont(new Font(Font.DIALOG, Font.PLAIN, 8));
        paintSector.setStroke(arrowStroke(style, icom));
        routeOrthogonally(paintSector, routeIndex, labelBounds, blockBounds, labeledStreams, stream.getName());
        paintSector.saveVisual();
    }

    private static Color arrowColor(String icom, String requested) {
        if (requested != null && requested.matches("#[0-9a-fA-F]{6}")) {
            Color selected = Color.decode(requested);
            if (selected.getRed() + selected.getGreen() + selected.getBlue() < 690) return selected;
        }
        if ("control".equalsIgnoreCase(icom)) return new Color(112, 83, 157);
        if ("mechanism".equalsIgnoreCase(icom)) return new Color(35, 126, 111);
        if ("output".equalsIgnoreCase(icom)) return new Color(49, 135, 91);
        if ("input".equalsIgnoreCase(icom)) return new Color(45, 105, 158);
        return new Color(92, 99, 110);
    }

    private static Stroke arrowStroke(String requested, String icom) {
        String style = requested == null ? "" : requested.toLowerCase(java.util.Locale.ROOT);
        if (!List.of("solid", "dashed", "dotted", "lightning").contains(style))
            style = "control".equalsIgnoreCase(icom) ? "dashed" : "solid";
        if ("lightning".equals(style)) {
            WayStroke stroke = new WayStroke(1f);
            stroke.setType(1);
            return stroke;
        }
        if ("dashed".equals(style))
            return new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                    new float[]{7f, 5f}, 0f);
        if ("dotted".equals(style))
            return new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                    new float[]{1.5f, 4f}, 0f);
        return new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /** Ramus stores arrow bends as points in the sector; use native IDEF0 orthogonal routing. */
    private static void routeOrthogonally(PaintSector sector, int routeIndex, List<FRectangle> labelPositions,
                                          List<FRectangle> blockBounds, java.util.Set<String> labeledStreams,
                                          String streamName) {
        Point start = sector.getStartPoint();
        Point end = sector.getEndPoint();
        double x1 = start.getX(), y1 = start.getY();
        double x2 = end.getX(), y2 = end.getY();
        com.ramussoft.pb.data.SectorBorder startBorder = sector.getSector().getStart();
        com.ramussoft.pb.data.SectorBorder endBorder = sector.getSector().getEnd();
        int startSide = startBorder.getFunction() != null ? startBorder.getFunctionType() : startBorder.getBorderType();
        int endSide = endBorder.getFunction() != null ? endBorder.getFunctionType() : endBorder.getBorderType();
        java.awt.geom.Point2D.Double startStub = stub(x1, y1, startSide, startBorder.getFunction() != null);
        java.awt.geom.Point2D.Double endStub = stub(x2, y2, endSide, endBorder.getFunction() != null);
        List<java.awt.geom.Point2D.Double> path = ArrowRouter.route(startStub, endStub, blockBounds);
        List<Point> points = new ArrayList<>();
        points.add(start);
        for (int i = 0; i < path.size(); i++) {
            java.awt.geom.Point2D.Double p = path.get(i);
            java.awt.geom.Point2D.Double next = i + 1 < path.size() ? path.get(i + 1) : new java.awt.geom.Point2D.Double(x2, y2);
            addBend(points, p.x, p.y, Math.abs(p.x - next.x) < 0.01 ? Ordinate.TYPE_Y : Ordinate.TYPE_X);
        }
        points.add(end);
        for (int i = points.size() - 2; i > 0; i--) {
            Point a = points.get(i - 1), b = points.get(i), c = points.get(i + 1);
            if ((Math.abs(a.getX() - b.getX()) < 0.01 && Math.abs(b.getX() - c.getX()) < 0.01)
                    || (Math.abs(a.getY() - b.getY()) < 0.01 && Math.abs(b.getY() - c.getY()) < 0.01))
                points.remove(i);
        }
        // Ramus determines whether a segment is horizontal by shared
        // Ordinate identity, not just by equal coordinates.
        for (int i = 1; i < points.size() - 1; i++) {
            Point previous = points.get(i - 1), current = points.get(i);
            if (Math.abs(previous.getY() - current.getY()) < 0.01)
                current.setYOrdinate(previous.getYOrdinate());
            else current.setXOrdinate(previous.getXOrdinate());
        }
        Point beforeEnd = points.get(points.size() - 2);
        if (Math.abs(beforeEnd.getY() - end.getY()) < 0.01)
            beforeEnd.setYOrdinate(end.getYOrdinate());
        else beforeEnd.setXOrdinate(end.getXOrdinate());
        sector.setPoints(points.toArray(new Point[0]));
        positionLabel(sector, labelPositions, blockBounds, List.of(), labeledStreams, streamName);
        sector.savePointOrdinates();
    }

    private static java.awt.geom.Point2D.Double stub(double x, double y, int side, boolean isFunction) {
        int sign = isFunction ? 1 : -1;
        if (side == com.ramussoft.pb.idef.visual.MovingPanel.LEFT) x -= sign * 8;
        else if (side == com.ramussoft.pb.idef.visual.MovingPanel.RIGHT) x += sign * 8;
        else if (side == com.ramussoft.pb.idef.visual.MovingPanel.TOP) y -= sign * 8;
        else if (side == com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM) y += sign * 8;
        return new java.awt.geom.Point2D.Double(x, y);
    }

    private static void positionLabel(PaintSector sector, List<FRectangle> occupied,
                                      List<FRectangle> blocks, List<FRectangle> lines, java.util.Set<String> labeledStreams,
                                      String streamName) {
        if (labeledStreams.contains(streamName)) return;
        Point[] points = sector.getPoints();
        double longest = 0, anchorX = Double.NaN, anchorY = Double.NaN;
        for (int i = 1; i < points.length; i++) {
            Point a = points[i - 1], b = points[i];
            double length = Math.abs(b.getX() - a.getX()) + Math.abs(b.getY() - a.getY());
            if (length > 8) {
                if (length > longest) {
                    longest = length;
                    anchorX = (a.getX() + b.getX()) / 2;
                    anchorY = (a.getY() + b.getY()) / 2;
                }
            }
        }
        if (longest > 8) {
            if (sector.isOnMe(anchorX, anchorY) == null) {
                StringBuilder geometry = new StringBuilder(streamName + " anchor=" + anchorX + "," + anchorY);
                for (int i = 0; i < sector.getPinCount(); i++) {
                    PaintSector.Pin pin = sector.getPin(i);
                    geometry.append(" [").append(pin.getStart().getX()).append(",").append(pin.getStart().getY())
                            .append(" -> ").append(pin.getEnd().getX()).append(",").append(pin.getEnd().getY())
                            .append(" type=").append(pin.getType()).append("]");
                }
                throw new IllegalStateException("Не удалось привязать подпись: " + geometry);
            }
            sector.setTildaPos(anchorX, anchorY);
            com.ramussoft.pb.idef.visual.MovingText text = sector.getText();
            if (text == null) throw new IllegalStateException("Ramus не создал подпись стрелки: " + streamName);
            // MovingLabel derives its font from the sector, not from the text
            // object. Ask Ramus to measure the wrapped label before placement.
            sector.setFont(new Font(Font.DIALOG, Font.PLAIN, 7));
            sector.saveVisual();
            text.setBounds(new FRectangle(0, 0, 105, 60));
            text.resetBounds();
            FRectangle measured = text.getBounds();
            double width = Math.max(30, measured.getWidth()), height = Math.max(10, measured.getHeight());
            FRectangle labelRect = placeLabel(anchorX, anchorY, width, height, occupied, blocks, lines,
                    sector.getMovingArea().getDoubleWidth(), sector.getMovingArea().getDoubleHeight());
            text.setBounds(labelRect);
            labeledStreams.add(streamName);
            occupied.add(labelRect);
        }
    }

    /** Place text independently of its tilda anchor; the native label otherwise
     * starts directly on the line and often covers a block or another label. */
    private static FRectangle placeLabel(double x, double y, double width, double height,
                                         List<FRectangle> occupied, List<FRectangle> blocks, List<FRectangle> lines,
                                         double pageWidth, double pageHeight) {
        double bestScore = Double.POSITIVE_INFINITY;
        FRectangle best = new FRectangle(24, 24, width, height);
        for (double leftCandidate = 12; leftCandidate <= pageWidth - width - 12; leftCandidate += 8) {
            for (double topCandidate = 12; topCandidate <= pageHeight - height - 12; topCandidate += 8) {
                    double left = leftCandidate;
                    double top = topCandidate;
                    // Keep a deliberate page margin instead of pinning labels
                    // to the corners when an anchor is near the canvas edge.
                    left = clamp(left, 12, pageWidth - width - 12);
                    top = clamp(top, 12, pageHeight - height - 12);
                    FRectangle candidate = new FRectangle(left, top, width, height);
                    double score = Math.abs((left + width / 2) - x) + Math.abs((top + height / 2) - y);
                    for (FRectangle block : blocks)
                        if (overlaps(candidate, block, 4)) score += 1000000;
                    for (FRectangle label : occupied)
                        if (overlaps(candidate, label, 4)) score += 1000000;
                    for (FRectangle line : lines)
                        if (overlaps(candidate, line, 2)) score += 10000;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
            }
        }
        return best;
    }

    private static boolean overlaps(FRectangle a, FRectangle b, double padding) {
        return a.getLeft() < b.getRight() + padding && a.getRight() + padding > b.getLeft()
                && a.getTop() < b.getBottom() + padding && a.getBottom() + padding > b.getTop();
    }

    private static void addBend(List<Point> points, double x, double y, int outgoingAxis) {
        Point previous = points.get(points.size() - 1);
        if (Math.abs(previous.getX() - x) < 1 && Math.abs(previous.getY() - y) < 1) return;
        Ordinate xOrdinate = new Ordinate(Ordinate.TYPE_X);
        xOrdinate.setPosition(x);
        Ordinate yOrdinate = new Ordinate(Ordinate.TYPE_Y);
        yOrdinate.setPosition(y);
        Point bend = new Point(xOrdinate, yOrdinate);
        bend.setType(outgoingAxis);
        points.add(bend);
    }

    private static int boundarySide(String endpoint) {
        return switch (endpoint) {
            case "@LEFT" -> com.ramussoft.pb.idef.visual.MovingPanel.LEFT;
            case "@RIGHT" -> com.ramussoft.pb.idef.visual.MovingPanel.RIGHT;
            case "@TOP" -> com.ramussoft.pb.idef.visual.MovingPanel.TOP;
            case "@BOTTOM" -> com.ramussoft.pb.idef.visual.MovingPanel.BOTTOM;
            default -> throw new IllegalArgumentException("Неизвестная граница диаграммы: " + endpoint);
        };
    }

    private static SectorRefactor.PerspectivePoint boundaryPoint(double x, double y, int side, int endpointType) {
        SectorRefactor.PerspectivePoint result = new SectorRefactor.PerspectivePoint();
        result.x = x;
        result.y = y;
        result.borderType = side;
        result.type = endpointType;
        return result;
    }

    private static SectorRefactor.PerspectivePoint functionPoint(Function function, double x, double y, int side, int endpointType) {
        Ordinate xOrdinate = new Ordinate(Ordinate.TYPE_X);
        Ordinate yOrdinate = new Ordinate(Ordinate.TYPE_Y);
        xOrdinate.setPosition(x);
        yOrdinate.setPosition(y);
        Point point = new Point(xOrdinate, yOrdinate);
        SectorRefactor.PerspectivePoint result = new SectorRefactor.PerspectivePoint();
        result.point = point;
        result.setFunction(function, side);
        result.type = endpointType;
        return result;
    }

    private static void layout(Function function, int index) {
        function.setBounds(new FRectangle(330, 210, 140, 74));
        function.setFont(new Font(Font.DIALOG, Font.PLAIN, 8));
        function.setBackground(new Color(231, 239, 250));
        function.setForeground(new Color(37, 54, 77));
    }

    private static void layoutStage(Function function, int index, int count, int longestCaption) {
        count = Math.max(1, count);
        int lines = Math.max(1, (int) Math.ceil(longestCaption / 26.0));
        // Every sibling gets the same geometry: per-title widths made the
        // independently centered blocks overlap when captions had different lengths.
        double width = clamp(190 - count * 12, 138, 180);
        double height = clamp(54 + lines * 9, 68, 80);
        double gap = count <= 1 ? 0 : Math.min(96, Math.max(64, (760 - count * width) / (count - 1)));
        double rowWidth = count * width + (count - 1) * gap;
        double startX = Math.max(24, (800 - rowWidth) / 2);
        double stagger = (index % 2 == 0 ? -18 : 18);
        function.setBounds(new FRectangle(startX + index * (width + gap), 225 + stagger, width, height));
        int ownLines = Math.max(1, (int) Math.ceil(function.getName().length() / 26.0));
        function.setFont(new Font(Font.DIALOG, Font.PLAIN, ownLines > 2 ? 8 : 9));
        function.setBackground(blockColor(function.getName()));
        function.setForeground(new Color(45, 52, 63));
    }

    private static void layoutChild(Function function, int index, int count, int longestCaption) {
        count = Math.max(1, count);
        int columns = Math.min(4, Math.max(1, (int) Math.ceil(Math.sqrt(count))));
        int rows = (count + columns - 1) / columns;
        int lines = Math.max(1, (int) Math.ceil(longestCaption / 26.0));
        double width = clamp(155 - Math.sqrt(count) * 9, 112, 145);
        double height = clamp(42 + lines * 7, 56, 66);
        double gapX = columns >= 4 ? 40 : 95;
        double gapY = rows <= 1 ? 0 : clamp((320 - rows * height) / (rows - 1), 24, 64);
        width = Math.min(width, (760 - (columns - 1) * gapX) / columns);
        height = Math.min(height, (352 - (rows - 1) * gapY) / rows);
        if (height < 34) throw new IllegalArgumentException("Слишком много работ для читаемого размещения на одном листе. Разделите модель на декомпозиции.");
        double totalWidth = columns * width + (columns - 1) * gapX;
        double totalHeight = rows * height + (rows - 1) * gapY;
        double startX = Math.max(24, (800 - totalWidth) / 2);
        // IDEF0's editable height is 444 at the standard page size, not 520.
        double startY = Math.max(40, (444 - totalHeight) / 2);
        int row = index / columns, column = index % columns;
        if (row % 2 == 1) column = columns - 1 - column;
        double stagger = rows > 1 ? ((column + row) % 3 - 1) * 7 : 0;
        function.setBounds(new FRectangle(startX + column * (width + gapX), startY + row * (height + gapY) + stagger, width, height));
        int ownLines = Math.max(1, (int) Math.ceil(function.getName().length() / 26.0));
        function.setFont(new Font(Font.DIALOG, Font.PLAIN, ownLines > 2 ? 8 : 9));
        function.setBackground(blockColor(function.getName()));
        function.setForeground(new Color(45, 52, 63));
    }

    /** Pale fills encode each block's IDEF0 role; arrow labels use matching hues. */
    private static Color blockColor(String name) {
        String text = normalize(name);
        if (text.contains("достав") || text.contains("разгруз")) return new Color(226, 243, 231);
        if (text.contains("транспорт") || text.contains("технич") || text.contains("охран")
                || text.contains("экспедитор")) return new Color(225, 242, 237);
        if (text.contains("логист") || text.contains("договор") || text.contains("документ")
                || text.contains("тариф") || text.contains("контрол") || text.contains("план"))
            return new Color(239, 233, 249);
        return new Color(227, 239, 250);
    }

    private static int nextPort(java.util.IdentityHashMap<Function, java.util.Map<Integer, Integer>> usage,
                                Function function, int side) {
        java.util.Map<Integer, Integer> sides = usage.computeIfAbsent(function, ignored -> new java.util.HashMap<>());
        int port = sides.getOrDefault(side, 0);
        sides.put(side, port + 1);
        return port;
    }

    private static double portOffset(int index) {
        if (index == 0) return 0;
        int magnitude = (index + 1) / 2;
        return (index % 2 == 1 ? 1 : -1) * magnitude * 14.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Row resolveFunction(java.util.Map<String, Row> functions, String requested) {
        Row exact = functions.get(requested);
        if (exact != null) return exact;
        String needle = normalize(requested);
        Row match = null;
        for (java.util.Map.Entry<String, Row> entry : functions.entrySet()) {
            String candidate = normalize(entry.getKey());
            if (candidate.equals(needle) || candidate.contains(needle) || needle.contains(candidate)) {
                if (match != null) return null; // ambiguous reference is unsafe
                match = entry.getValue();
            }
        }
        return match;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim().replaceAll("\\s+", " ");
    }

    public List<Element> createElements(Engine engine, String qualifierName, List<String> names) {
        Qualifier qualifier = engine.getQualifierByName(qualifierName);
        if (qualifier == null) throw new IllegalArgumentException("Unknown qualifier: " + qualifierName);
        Attribute nameAttribute = qualifier.getAttributeForName() < 0 ? null : engine.getAttribute(qualifier.getAttributeForName());
        List<Element> created = new ArrayList<>();
        for (String name : names) {
            Element element = engine.createElement(qualifier.getId());
            if (nameAttribute != null) engine.setAttribute(element, nameAttribute, name);
            created.add(element);
        }
        return created;
    }
}
