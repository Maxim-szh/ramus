package com.ramussoft.local.ai;

import com.ramussoft.common.Engine;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.FileDatabaseFactory;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.visual.MovingArea;
import java.io.File;
import java.lang.reflect.Method;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TransportDiagramIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void savesNamedConnectedArrowsInOneDiagram() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("java.awt.headless", "true");
        System.setProperty("user.home", temporary.newFolder("home").getAbsolutePath());
        File root = new File(System.getProperty("user.dir"));
        while (root != null && !new File(root, "settings.gradle").isFile()) root = root.getParentFile();
        assertNotNull(root);
        MemoryDatabase db = (MemoryDatabase) FileDatabaseFactory.createDatabase(new File(root, "dest/doc/en/Enterprise activity.rsf"));
        Engine engine = null;
        try {
            engine = db.getEngine(null);
            if (engine.getQualifierByName("Работы") == null) {
                for (com.ramussoft.common.Qualifier qualifier : engine.getQualifiers()) {
                    if (com.ramussoft.idef0.IDEF0Plugin.isFunction(qualifier)) {
                        qualifier.setName("Работы");
                        engine.updateQualifier(qualifier);
                        break;
                    }
                }
            }
            Method planMethod = Class.forName("com.ramussoft.local.ai.RamusAiPlugin$AssistantView")
                    .getDeclaredMethod("transportCompanyPlan");
            planMethod.setAccessible(true);
            AiPlan plan = (AiPlan) planMethod.invoke(null);
            new RamusToolExecutor().execute(engine, db.getAccessRules(null), plan);
            File saved = temporary.newFile("transport.rsf");
            ((FileIEngineImpl) engine.getDeligate()).saveToFile(saved);
            MemoryDatabase reopenedDatabase = (MemoryDatabase) FileDatabaseFactory.createDatabase(saved);
            Engine reopenedEngine = reopenedDatabase.getEngine(null);
            try {
            DataPlugin data = NDataPluginFactory.getDataPlugin(reopenedEngine.getQualifierByName("Работы"), reopenedEngine, reopenedDatabase.getAccessRules(null));
            Function rootFunction = (Function) data.getBaseFunction();
            Function context = null;
            for (int i = 0; i < rootFunction.getChildCount(); i++) {
                Function candidate = (Function) rootFunction.getChildAt(i);
                if ("Оказать услуги по перевозке грузов".equals(candidate.getName())) context = candidate;
            }
            assertNotNull(context);
            assertTrue(context.getChildCount() >= 9);
            MovingArea reopened = new MovingArea(data, context);
            reopened.setActiveFunction(context);
            int named = 0, connected = 0, tildas = 0;
            java.util.List<com.dsoft.pb.types.FRectangle> labelRectangles = new java.util.ArrayList<>();
            for (int i = 0; i < reopened.getRefactor().getSectorsCount(); i++) {
                PaintSector arrow = reopened.getRefactor().getSector(i);
                if (arrow.getStream() != null && arrow.getStream().getName() != null) named++;
                if (arrow.getSector().getStart().getFunction() != null && arrow.getSector().getEnd().getFunction() != null) connected++;
                if (arrow.isShowTilda() && arrow.getText() != null) tildas++;
                if (arrow.getText() != null) {
                    com.dsoft.pb.types.FRectangle label = arrow.getText().getBounds();
                    assertTrue("Label is outside the diagram: " + arrow.getText().getText(), label.getLeft() >= 0 && label.getTop() >= 0
                            && label.getRight() <= reopened.getDoubleWidth() && label.getBottom() <= reopened.getDoubleHeight());
                    for (int j = 0; j < context.getChildCount(); j++) {
                        Function block = (Function) context.getChildAt(j);
                        assertFalse("Label overlaps a block: " + arrow.getText().getText() + " / " + block.getName(), overlaps(label, block.getBounds()));
                    }
                    for (com.dsoft.pb.types.FRectangle other : labelRectangles)
                        assertFalse("Arrow labels overlap: " + arrow.getText().getText(), overlaps(label, other));
                    for (int j = 0; j < reopened.getRefactor().getSectorsCount(); j++) {
                        com.ramussoft.pb.idef.elements.Point[] points = reopened.getRefactor().getSector(j).getPoints();
                        for (int k = 1; k < points.length; k++) {
                            com.ramussoft.pb.idef.elements.Point a = points[k - 1], b = points[k];
                            com.dsoft.pb.types.FRectangle line = new com.dsoft.pb.types.FRectangle(
                                    Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                                    Math.max(0.1, Math.abs(a.getX() - b.getX())), Math.max(0.1, Math.abs(a.getY() - b.getY())));
                            assertFalse("Arrow label crosses a line: " + arrow.getText().getText(), overlaps(label, line));
                        }
                    }
                    labelRectangles.add(label);
                }
            }
            assertTrue("No named arrows after reopening", named >= 18);
            assertTrue("No internal block connections after reopening", connected >= 12);
            assertTrue("No labels with tildes after reopening", tildas > 0);
            com.ramussoft.pb.print.PIDEF0painter painter = new com.ramussoft.pb.print.PIDEF0painter(
                    context, new java.awt.Dimension(1600, 1200), data);
            javax.imageio.ImageIO.write(painter.createImage(null), "png", new File("build/transport-test.png"));
            } finally {
                ((FileIEngineImpl) reopenedEngine.getDeligate()).close();
                reopenedDatabase.close();
            }
        } finally {
            if (engine != null) ((FileIEngineImpl) engine.getDeligate()).close();
            db.close();
            System.setProperty("user.home", previousHome);
        }
    }

    private static boolean overlaps(com.dsoft.pb.types.FRectangle a, com.dsoft.pb.types.FRectangle b) {
        return a.getLeft() < b.getRight() && a.getRight() > b.getLeft()
                && a.getTop() < b.getBottom() && a.getBottom() > b.getTop();
    }
}
