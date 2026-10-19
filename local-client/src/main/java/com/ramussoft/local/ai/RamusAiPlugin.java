package com.ramussoft.local.ai;

import com.ramussoft.gui.common.*;
import com.ramussoft.gui.common.event.ActionEvent;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.idef0.IDEF0ViewPlugin;
import com.ramussoft.idef0.OpenDiagram;

/** Ramus UI entry point for the separate AI agent. It performs no model writes. */
public final class RamusAiPlugin extends AbstractViewPlugin {
    public static final String VIEW_ID = "RamusAI";
    private static final String OPEN_ACTION = "RamusAI.Open";
    private final RamusAiClient client = new RamusAiClient();
    @Override public String getName() { return "AI Assistant"; }
    @Override public String getString(String key) {
        if (VIEW_ID.equals(key) || OPEN_ACTION.equals(key)) return "AI Assistant";
        if ((OPEN_ACTION + ".Short").equals(key) || (OPEN_ACTION + ".Long").equals(key))
            return "Создание модели с помощью ИИ";
        String value = com.ramussoft.gui.common.GlobalResourcesManager.getString(key);
        return value == null ? key : value;
    }
    @Override public UniqueView[] getUniqueViews() { return new UniqueView[]{new AssistantView(framework, client)}; }
    @Override public ActionDescriptor[] getActionDescriptors() {
        ActionDescriptor d = new ActionDescriptor(); d.setMenu("Tools");
        d.setAction(new AbstractAction("AI Assistant") { { putValue(Action.ACTION_COMMAND_KEY, OPEN_ACTION); } public void actionPerformed(java.awt.event.ActionEvent e) {
            framework.openView(new ActionEvent(ActionEvent.OPEN_STATIC_VIEW, VIEW_ID));
        }});
        return new ActionDescriptor[]{d};
    }
    private static final class AssistantView extends AbstractUniqueView {
        private final RamusAiClient client;
        private final RamusToolExecutor tools = new RamusToolExecutor();
        private final AiPlanParser planParser = new AiPlanParser();
        private final ModelSnapshotProvider snapshots = new ModelSnapshotProvider();
        private final JTextArea request = new JTextArea(6, 42);
        private final JTextArea result = new JTextArea(8, 42);
        AssistantView(GUIFramework f, RamusAiClient c) { super(f); client = c; result.setEditable(false); result.setLineWrap(true); result.setWrapStyleWord(true); }
        @Override public String getId() { return VIEW_ID; }
        // Reuse the built-in workspace until the plugin has a persisted workspace definition.
        @Override public String getDefaultWorkspace() { return "Workspace.IDEF0"; }
        @Override public String getString(String key) { return key; }
        @Override public Action[] getActions() { return new Action[0]; }
        @Override public JComponent createComponent() {
            JPanel root = new JPanel(new BorderLayout(10, 10)); root.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
            root.setMinimumSize(new Dimension(320, 260));
            JComboBox<String> provider = new JComboBox<>(new String[]{"DeepSeek (облако)", "Локальная LLM"});
            JPasswordField apiKey = new JPasswordField();
            apiKey.setToolTipText("Ключ используется только в текущем сеансе и не сохраняется");
            JTextField endpoint = new JTextField("http://127.0.0.1:11434/v1");
            JTextField model = new JTextField("deepseek-flash");
            JPanel fields = new JPanel(new GridLayout(0, 2, 8, 5));
            fields.add(new JLabel("Источник ИИ")); fields.add(provider);
            fields.add(new JLabel("Модель")); fields.add(model);
            fields.add(new JLabel("DeepSeek API-ключ")); fields.add(apiKey);
            fields.add(new JLabel("Адрес локального API")); fields.add(endpoint);
            JTextArea privacy = new JTextArea("DeepSeek получает задание и названия элементов проекта.");
            privacy.setEditable(false); privacy.setFocusable(false); privacy.setOpaque(false);
            privacy.setLineWrap(true); privacy.setWrapStyleWord(true);
            privacy.setFont(UIManager.getFont("Label.font"));
            endpoint.setEnabled(false);
            provider.addActionListener(e -> {
                boolean local = provider.getSelectedIndex() == 1;
                endpoint.setEnabled(local); apiKey.setEnabled(!local);
                model.setText(local ? "" : "deepseek-flash");
                model.setToolTipText(local ? "Название установленной локальной модели, например llama3.2" : "Название модели DeepSeek");
                privacy.setText(local ? "Данные отправляются на указанный локальный сервис." : "DeepSeek получает задание и названия элементов проекта.");
            });
            JPanel center = new JPanel(new BorderLayout(4,4));
            center.setBorder(BorderFactory.createTitledBorder("Задание"));
            request.setLineWrap(true); request.setWrapStyleWord(true);
            request.setToolTipText("Опишите процесс и желаемую диаграмму");
            center.add(new JScrollPane(request), BorderLayout.CENTER);
            JPanel bottom = new JPanel(new BorderLayout(4,4)); JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton generate = new JButton("Сгенерировать");
            generate.addActionListener(e -> {
                boolean local = provider.getSelectedIndex() == 1;
                AgentProfile profile = new AgentProfile(local ? "Локальная LLM" : "DeepSeek",
                        local ? "local-llm" : "deepseek", local ? endpoint.getText().trim() : "https://api.deepseek.com",
                        local, "", model.getText().trim());
                analyze(root, profile, new String(apiKey.getPassword()), generate);
            });
            result.setLineWrap(true); result.setWrapStyleWord(true);
            bottom.setBorder(BorderFactory.createTitledBorder("Результат и журнал выполнения"));
            buttons.add(generate); buttons.add(new JLabel("Изменения — только после подтверждения")); bottom.add(buttons, BorderLayout.NORTH); bottom.add(new JScrollPane(result), BorderLayout.CENTER);
            JSplitPane workArea = new JSplitPane(JSplitPane.VERTICAL_SPLIT, center, bottom);
            workArea.setResizeWeight(0.55); workArea.setContinuousLayout(true); workArea.setOneTouchExpandable(true);
            JPanel header = new JPanel(new BorderLayout(0, 6));
            header.add(fields, BorderLayout.CENTER); header.add(privacy, BorderLayout.SOUTH);
            root.add(header, BorderLayout.NORTH); root.add(workArea, BorderLayout.CENTER); return root;
        }
        private void analyze(Component parent, AgentProfile profile, String enteredApiKey, JButton generate) {
            if (request.getText().isBlank()) { result.setText("Опишите задание перед генерацией."); return; }
            if (profile.model().isBlank()) { result.setText("Укажите название модели."); return; }
            if ("deepseek".equals(profile.type()) && !enteredApiKey.isBlank()) {
                profile = new AgentProfile(profile.name(), profile.type(), profile.endpoint(), profile.localOnly(), enteredApiKey, profile.model());
            }
            if ("deepseek".equals(profile.type()) && profile.apiKey().isBlank()) {
                JPasswordField key = new JPasswordField();
                int choice = JOptionPane.showConfirmDialog(parent, key,
                        "Введите DeepSeek API key (не сохраняется)", JOptionPane.OK_CANCEL_OPTION);
                if (choice != JOptionPane.OK_OPTION || key.getPassword().length == 0) {
                    result.setText("Введите API-ключ DeepSeek в поле настроек и повторите попытку.");
                    return;
                }
                profile = new AgentProfile(profile.name(), profile.type(), profile.endpoint(), profile.localOnly(),
                        new String(key.getPassword()), profile.model());
            }
            final AgentProfile selectedProfile = profile;
            final String prompt = request.getText();
            generate.setEnabled(false);
            result.setText("Подготавливаю данные проекта и запрашиваю план...");
            new SwingWorker<String,Void>() {
                protected String doInBackground() throws Exception {
                    ModelSnapshot snapshot = snapshots.capture(framework.getEngine());
                    if ("deepseek".equals(selectedProfile.type())) return new DeepSeekClient().generatePlan(prompt, snapshot, selectedProfile.apiKey(), selectedProfile.model());
                    return new LocalLlmClient(selectedProfile.endpoint()).generatePlan(prompt, snapshot, selectedProfile.model());
                }
                protected void done() { try {
                    String answer = get();
                    {
                        boolean transportTemplate = isTransportCompanyTask(prompt);
                        AiPlan plan = transportTemplate ? transportCompanyPlan() : planParser.parse(answer);
                        result.setText((transportTemplate ? "Для транспортной компании используется встроенный шаблон, а не план, возвращённый моделью.\n\n" : "") + describePlan(plan));
                        int approval = JOptionPane.showConfirmDialog(parent,
                                "Применить показанный план к открытому проекту?\n"
                                + "Существующие одноимённые блоки могут быть перемещены, а стрелки заменены.\n"
                                + "Рекомендуется сначала сохранить копию проекта.",
                                "Подтверждение изменений", JOptionPane.OK_CANCEL_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (approval != JOptionPane.OK_OPTION) {
                            result.append("\n\nИзменения не применены.");
                            return;
                        }
                        result.setText("План готов. Применяю всю модель одним запуском...");
                        try {
                            // Ramus' IDEF0 model and diagram geometry are GUI-backed APIs;
                            // mutate them on the EDT as one ordered operation.
                            java.util.List<com.ramussoft.common.Element> created = tools.execute(
                                    framework.getEngine(), framework.getAccessRules(), plan,
                                    message -> result.append("\n• " + message));
                            NDataPluginFactory.fullRefrash(framework);
                            com.ramussoft.common.Qualifier modelQualifier = framework.getEngine().getQualifierByName("Работы");
                            if (modelQualifier != null) {
                                com.ramussoft.pb.DataPlugin modelData = NDataPluginFactory.getDataPlugin(modelQualifier, framework.getEngine(), framework.getAccessRules());
                                com.ramussoft.pb.Function base = (com.ramussoft.pb.Function) modelData.getBaseFunction();
                                long contextId = -1L;
                                int bestChildCount = -1;
                                int selectedChildCount = -1;
                                for (int i = 0; i < base.getChildCount(); i++) {
                                    com.ramussoft.pb.Row row = (com.ramussoft.pb.Row) base.getChildAt(i);
                                    if (row instanceof com.ramussoft.pb.Function) {
                                        if (normalizeModelName("Оказать услуги по перевозке грузов")
                                                .equals(normalizeModelName(row.getName()))) {
                                            contextId = row.getElement().getId();
                                            selectedChildCount = row.getChildCount();
                                            break;
                                        }
                                        if (row.getChildCount() > bestChildCount) {
                                            bestChildCount = row.getChildCount();
                                            contextId = row.getElement().getId();
                                            selectedChildCount = bestChildCount;
                                        }
                                    }
                                }
                                long expectedStages = plan.operations().stream()
                                        .filter(op -> "create_element".equals(op.tool())
                                                && op.qualifier().startsWith("Работы::")
                                                && !op.qualifier().substring("Работы::".length()).contains("::"))
                                        .count();
                                if (contextId < 0 || selectedChildCount < expectedStages)
                                    throw new IllegalStateException("Не нашёл контекстную диаграмму с этапами. Перестроение остановлено, чтобы не открывать пустой уровень A-0.");
                                framework.propertyChanged(IDEF0ViewPlugin.OPEN_DIAGRAM,
                                        new OpenDiagram(modelQualifier, contextId, true));
                            }
                            long contextCount = plan.operations().stream().filter(op -> "create_element".equals(op.tool()) && "Работы".equals(op.qualifier())).count();
                            long stageCount = plan.operations().stream().filter(op -> "create_element".equals(op.tool()) && op.qualifier().startsWith("Работы::") && !op.qualifier().substring("Работы::".length()).contains("::")).count();
                            long detailCount = plan.operations().stream().filter(op -> "create_element".equals(op.tool()) && op.qualifier().startsWith("Работы::") && op.qualifier().substring("Работы::".length()).contains("::")).count();
                            long functionCount = contextCount + stageCount + detailCount;
                            long streamCount = plan.operations().stream().filter(op -> "create_stream".equals(op.tool())).count();
                            result.append("\n\nГотово. Функций: " + functionCount + " (контекст: " + contextCount + ", блоков на единой A0: " + stageCount + ", вложенных блоков: " + detailCount + "), стрелок: " + streamCount + ". Диаграмма открыта с масштабом «вписать целиком».");
                        } catch (Exception e) {
                            result.append("\nОшибка выполнения: " + e.getMessage());
                        }
                    }
                } catch (Exception e) { Throwable cause = e.getCause() == null ? e : e.getCause(); result.setText("Не удалось сформировать план: " + cause.getMessage()); }
                finally { generate.setEnabled(true); } }
            }.execute();
        }
        private static boolean isTransportCompanyTask(String prompt) {
            String text = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
            return text.contains("транспорт") && (text.contains("компан") || text.contains("груз"));
        }
        private static String normalizeModelName(String value) {
            return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim().replaceAll("\\s+", " ");
        }
        private static AiPlan transportCompanyPlan() {
            java.util.List<AiPlan.AiOperation> operations = new ArrayList<>();
            String context = "Оказать услуги по перевозке грузов";
            String contract = "Оформить договор и согласовать тарифы";
            String prepare = "Организовать подготовку и перевозку груза";
            String deliver = "Доставить груз и подтвердить исполнение договора";
            addFunction(operations, "Работы", context);
            addBoundaryIn(operations, "Потоки", "Заявка клиента и характеристики груза", "@LEFT", context);
            addBoundaryIn(operations, "Потоки", "Груз клиента для передачи на перевозку", "@LEFT", context);
            addBoundaryIn(operations, "Потоки", "Правила перевозки, тарифы, сроки и требования к сохранности", "@TOP", context);
            addBoundaryIn(operations, "Потоки", "Персонал, транспортный парк, склад и средства погрузки", "@BOTTOM", context);
            addBoundaryOut(operations, "Потоки", "Доставленный груз и подтверждение исполнения договора", context, "@RIGHT");

            String a0Streams = "Потоки::" + context;
            String receive = "Принять груз и сверить условия договора";
            String prepareCargo = "Подготовить груз и организовать хранение";
            String maintenance = "Обеспечить исправность транспортных средств";
            String logistics = "Спланировать логистику и маршрут";
            String storage = "Хранить груз до согласованной отправки";
            String load = "Погрузить груз и оформить отправку";
            String deliverCargo = "Доставить и разгрузить груз в срок";
            String tracking = "Контролировать перевозку, охрану и сопровождение";
            // All operational blocks are siblings on one compact A0 canvas.
            // Keep the conventional one-block context diagram A-0 separately.
            addFunctions(operations, context, contract, receive, prepareCargo, maintenance,
                    logistics, storage, load, deliverCargo, tracking);
            addStream(operations, a0Streams, "Договор, тарифы и требования к перевозке", contract, receive, "control");
            addStream(operations, a0Streams, "Принятый груз и параметры перевозки", receive, prepareCargo);
            addStream(operations, a0Streams, "Подготовленный груз для хранения", prepareCargo, storage);
            addStream(operations, a0Streams, "Груз, готовый к погрузке", storage, load);
            addStream(operations, a0Streams, "Маршрут и график подачи транспорта", logistics, load, "control");
            addStream(operations, a0Streams, "Исправный транспорт и водитель", maintenance, logistics, "mechanism");
            addStream(operations, a0Streams, "Погруженный груз и отправочные документы", load, deliverCargo);
            addStream(operations, a0Streams, "Адрес, срок и условия доставки", contract, deliverCargo, "control");
            addStream(operations, a0Streams, "Фактическое местоположение и статус груза", deliverCargo, tracking);
            addStream(operations, a0Streams, "Отклонения маршрута и корректировки логистики", tracking, logistics, "control");
            addStream(operations, a0Streams, "Заказ охраны или экспедиторского сопровождения", contract, tracking, "control");
            addStream(operations, a0Streams, "Охрана и сопровождение при доставке", tracking, deliverCargo, "mechanism");
            addBoundaryIn(operations, a0Streams, "Заявка клиента и договорные сведения", "@LEFT", contract);
            addBoundaryIn(operations, a0Streams, "Груз, переданный клиентом", "@LEFT", receive);
            addBoundaryIn(operations, a0Streams, "Правила перевозки и требования к сохранности", "@TOP", logistics);
            addBoundaryIn(operations, a0Streams, "Транспортный парк и персонал", "@BOTTOM", maintenance);
            addBoundaryIn(operations, a0Streams, "Склад и средства погрузки", "@BOTTOM", load);
            addBoundaryOut(operations, a0Streams, "Доставленный груз и подтверждение исполнения", deliverCargo, "@RIGHT");
            return new AiPlan("IDEF0: все операционные блоки и связи на одном листе A0; A-0 содержит только контекст", operations);
        }
        private static void addFunction(java.util.List<AiPlan.AiOperation> operations, String qualifier, String name) {
            operations.add(new AiPlan.AiOperation("create_element", qualifier, name, "", "", "", "", ""));
        }
        private static void addFunctions(java.util.List<AiPlan.AiOperation> operations, String diagramPath, String... names) {
            for (String name : names) addFunction(operations, "Работы::" + diagramPath, name);
        }
        private static void addStream(java.util.List<AiPlan.AiOperation> operations, String qualifier, String name, String source, String target) {
            addStream(operations, qualifier, name, source, target, "input");
        }
        private static void addStream(java.util.List<AiPlan.AiOperation> operations, String qualifier, String name, String source, String target, String icom) {
            operations.add(new AiPlan.AiOperation("create_stream", qualifier, name, source, target, icom, "", ""));
        }
        private static void addBoundaryIn(java.util.List<AiPlan.AiOperation> operations, String qualifier, String name, String border, String target) {
            operations.add(new AiPlan.AiOperation("create_stream", qualifier, name, border, target, border.substring(1).toLowerCase(java.util.Locale.ROOT), "", ""));
        }
        private static void addBoundaryOut(java.util.List<AiPlan.AiOperation> operations, String qualifier, String name, String source, String border) {
            operations.add(new AiPlan.AiOperation("create_stream", qualifier, name, source, border, border.substring(1).toLowerCase(java.util.Locale.ROOT), "", ""));
        }
        private static String describePlan(AiPlan plan) {
            StringBuilder text = new StringBuilder(plan.summary()).append('\n');
            for (AiPlan.AiOperation operation : plan.operations()) {
                String parent = operation.qualifier().contains("::") ? operation.qualifier().substring(operation.qualifier().indexOf("::") + 2) : "";
                text.append("• ");
                if (!parent.isEmpty()) text.append("[").append(parent).append("] ");
                text.append("create_element".equals(operation.tool()) ? "Блок: " : "Стрелка: ")
                        .append(operation.name());
                if ("create_stream".equals(operation.tool()))
                    text.append(" ( ").append(operation.source()).append(" → ").append(operation.target()).append(" )");
                text.append('\n');
            }
            return text.toString();
        }
    }
}
