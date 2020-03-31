package com.structurizr.graphviz;

import com.structurizr.model.DeploymentNode;
import com.structurizr.model.Element;
import com.structurizr.view.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Parses an SVG file created by graphviz, extracts the layout information, and applies it to a Structurizr view.
 */
class SVGReader {

    private File path;
    private boolean changePaperSize;

    private int margin;

    SVGReader(File path, int margin, boolean changePaperSize) {
        this.path = path;
        this.margin = margin;
        this.changePaperSize = changePaperSize;
    }

    void parseAndApplyLayout(View view) throws Exception {
        File file = new File(path, view.getKey() + ".dot.svg");
        System.out.println(" - Parsing " + file.getAbsolutePath());

        FileInputStream fileIS = new FileInputStream(file);
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = builderFactory.newDocumentBuilder();
        Document xmlDocument = builder.parse(fileIS);
        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList nodeList = (NodeList)xPath.compile("/svg/g[@class=\"graph\"]").evaluate(xmlDocument, XPathConstants.NODESET);
        String transform = nodeList.item(0).getAttributes().getNamedItem("transform").getNodeValue();
        String translate = transform.substring(transform.indexOf("translate"));
        String numbers = translate.substring(translate.indexOf("(") + 1, translate.indexOf(")"));
        int transformX = Integer.parseInt(numbers.split(" ")[0]);
        int transformY = Integer.parseInt(numbers.split(" ")[1]);

        int minimumX = Integer.MAX_VALUE;
        int minimumY = Integer.MAX_VALUE;
        int maximumX = Integer.MIN_VALUE;
        int maximumY = Integer.MIN_VALUE;

        for (ElementView elementView : view.getElements()) {
            if (elementView.getElement() instanceof DeploymentNode) {
                // deployment nodes are clusters, so positioned automatically
                continue;
            }

            String expression = String.format("/svg/g/g[@id=\"%s\"]/polygon", elementView.getId());
            nodeList = (NodeList)xPath.compile(expression).evaluate(xmlDocument, XPathConstants.NODESET);
            if (nodeList.getLength() == 0) {
                continue;
            }

            String pointsAsString = nodeList.item(0).getAttributes().getNamedItem("points").getNodeValue();
            String[] points = pointsAsString.split(" ");
            String[] coordinates = points[1].split(",");

            double x = Double.parseDouble(coordinates[0]) + transformX;
            double y = Double.parseDouble(coordinates[1]) + transformY;

            elementView.setX((int)(x * Constants.DPI_RATIO));
            elementView.setY((int)(y * Constants.DPI_RATIO));

            minimumX = Math.min(elementView.getX(), minimumX);
            minimumY = Math.min(elementView.getY(), minimumY);
            maximumX = Math.max(elementView.getX() + getElementWidth(view, elementView.getId()), maximumX);
            maximumY = Math.max(elementView.getY() + getElementHeight(view, elementView.getId()), maximumY);
        }

        for (RelationshipView relationshipView : view.getRelationships()) {
            String expression = String.format("/svg/g/g[@id=\"%s\"]/path", relationshipView.getId());
            nodeList = (NodeList)xPath.compile(expression).evaluate(xmlDocument, XPathConstants.NODESET);
            if (nodeList.getLength() == 0) {
                continue;
            }

            String dAsString = nodeList.item(0).getAttributes().getNamedItem("d").getNodeValue();
            String[] d = dAsString.split(" ");

            Set<Vertex> vertices = new LinkedHashSet<>();

            if (d.length == 3) {
                relationshipView.setVertices(vertices);
            } else {
                for (int i = 1; i < d.length - 2; i++) {
                    double x = Double.parseDouble(d[i].split(",")[0]) + transformX;
                    double y = Double.parseDouble(d[i].split(",")[1]) + transformY;
                    Vertex vertex = new Vertex((int)(x * Constants.DPI_RATIO), (int)(y * Constants.DPI_RATIO));
                    vertices.add(vertex);

                    minimumX = Math.min(vertex.getX(), minimumX);
                    minimumY = Math.min(vertex.getY(), minimumY);
                    maximumX = Math.max(vertex.getX(), maximumX);
                    maximumY = Math.max(vertex.getY(), maximumY);
                }
                relationshipView.setVertices(vertices);
            }
        }

        // also take into account any clusters that might be rendered outside the nodes
        String expression = "/svg/g/g[@class=\"cluster\"]/polygon";
        nodeList = (NodeList)xPath.compile(expression).evaluate(xmlDocument, XPathConstants.NODESET);
        for (int i = 0; i < nodeList.getLength(); i++) {
            String[] points = nodeList.item(i).getAttributes().getNamedItem("points").getNodeValue().split(" ");
            for (String point : points) {
                int x = (int)((Double.parseDouble(point.split(",")[0]) + transformX) * Constants.DPI_RATIO);
                int y = (int)((Double.parseDouble(point.split(",")[1]) + transformY) * Constants.DPI_RATIO);

                minimumX = Math.min(x, minimumX);
                minimumY = Math.min(y, minimumY);
                maximumX = Math.max(x, maximumX);
                maximumY = Math.max(y, maximumY);
            }
        }

        if (changePaperSize) {
            PaperSize.Orientation orientation = (maximumX > maximumY) ? PaperSize.Orientation.Landscape : PaperSize.Orientation.Portrait;

            for (PaperSize paperSize : PaperSize.getOrderedPaperSizes(orientation)) {
                if (paperSize.getWidth() > (maximumX + margin + margin) && paperSize.getHeight() > (maximumY + margin + margin)) {
                    view.setPaperSize(paperSize);
                    break;
                }
            }
        }

        int deltaX = (view.getPaperSize().getWidth() - maximumX + minimumX) / 2;
        int deltaY = (view.getPaperSize().getHeight() - maximumY + minimumY) / 2;

        // move everything relative to 0,0
        for (ElementView elementView : view.getElements()) {
            elementView.setX(elementView.getX() - minimumX);
            elementView.setY(elementView.getY() - minimumY);
        }
        for (RelationshipView relationshipView : view.getRelationships()) {
            for (Vertex vertex : relationshipView.getVertices()) {
                vertex.setX(vertex.getX() - minimumX);
                vertex.setY(vertex.getY() - minimumY);
            }
        }

        // and now centre everything
        for (ElementView elementView : view.getElements()) {
            elementView.setX(elementView.getX() + deltaX);
            elementView.setY(elementView.getY() + deltaY);
        }
        for (RelationshipView relationshipView : view.getRelationships()) {
            for (Vertex vertex : relationshipView.getVertices()) {
                vertex.setX(vertex.getX() + deltaX);
                vertex.setY(vertex.getY() + deltaY);
            }
        }

        System.out.println(" - Done");
    }

    private int getElementWidth(View view, String elementId) {
        Element element = view.getModel().getElement(elementId);
        return view.getViewSet().getConfiguration().getStyles().findElementStyle(element).getWidth();
    }

    private int getElementHeight(View view, String elementId) {
        Element element = view.getModel().getElement(elementId);
        return view.getViewSet().getConfiguration().getStyles().findElementStyle(element).getHeight();
    }

}