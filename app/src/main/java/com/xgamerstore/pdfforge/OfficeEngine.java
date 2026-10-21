package com.xgamerstore.pdfforge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Small offline Office converters based on Android's native PDF canvas and
 * OOXML (ZIP + XML). No network and no server dependency.
 *
 * Intentional fidelity limits: DOCX paragraphs/simple tables and text styling
 * only; PPTX text per slide; XLSX cell values (first worksheet) and CSV.
 * PDF -> DOCX reconstructs an editable text document, NOT the original layout.
 */
final class OfficeEngine {
    enum Action {
        DOCX_PDF, PDF_DOCX, DOCX_TXT, TXT_PDF,
        XLSX_PDF, XLSX_CSV, CSV_XLSX, CSV_PDF, PPTX_PDF
    }

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final int MAX_ARCHIVE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 2_000_000;
    private OfficeEngine() {}

    static void execute(Context context, Action action, Uri input, Uri output) throws Exception {
        try (OutputStream destination = context.getContentResolver().openOutputStream(output, "w")) {
            if (destination == null) throw new IOException("Unable to create output file.");
            switch(action) {
                case DOCX_PDF:
                    renderPdf(officeText(readArchive(context,input),Action.DOCX_PDF), destination);
                    break;
                case DOCX_TXT:
                    destination.write(join(officeText(readArchive(context,input),Action.DOCX_TXT))
                            .getBytes(StandardCharsets.UTF_8));
                    break;
                case PDF_DOCX:
                    writeDocx(readPdfText(context,input), destination);
                    break;
                case TXT_PDF:
                    renderPdf(plainText(context,input), destination);
                    break;
                case XLSX_PDF:
                    renderPdf(officeText(readArchive(context,input),Action.XLSX_PDF),destination);
                    break;
                case XLSX_CSV:
                    writeCsv(parseXlsx(readArchive(context,input)),destination);
                    break;
                case CSV_XLSX:
                    writeXlsx(readCsv(context,input),destination);
                    break;
                case CSV_PDF:
                    renderPdf(csvSegments(readCsv(context,input)),destination);
                    break;
                case PPTX_PDF:
                    renderPdf(officeText(readArchive(context,input),Action.PPTX_PDF),destination);
                    break;
            }
            destination.flush();
        }
    }

    private static String readPdfText(Context context, Uri uri) throws IOException {
        try(InputStream input = context.getContentResolver().openInputStream(uri)) {
            if(input==null)throw new IOException("Cannot open PDF.");
            try(PDDocument doc=PDDocument.load(input)) {
                String value = new PDFTextStripper().getText(doc);
                if(value.length()>MAX_TEXT_CHARS) throw new IOException("Document too large for text conversion.");
                return value;
            }
        }
    }

    private static List<Section> plainText(Context context, Uri uri) throws IOException {
        List<Section> items = new ArrayList<>();
        String str;
        try(InputStream input=context.getContentResolver().openInputStream(uri)){
            if(input==null)throw new IOException("Cannot read text file.");
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            byte[] chunk=new byte[8192];int n;
            while((n=input.read(chunk))!=-1){
                if(buffer.size()+n>MAX_TEXT_CHARS*4)throw new IOException("Text file is too large.");
                buffer.write(chunk,0,n);
            }
            str=new String(buffer.toByteArray(),StandardCharsets.UTF_8);
        }
        if(str.startsWith("\uFEFF"))str=str.substring(1);
        for(String line:str.split("\\R",-1))items.add(new Section(line,false));
        return items;
    }

    private static Map<String,byte[]> readArchive(Context context, Uri uri) throws IOException {
        Map<String,byte[]> result = new HashMap<>();
        int total=0,count=0;
        try(InputStream stream=context.getContentResolver().openInputStream(uri)){
            if(stream==null)throw new IOException("Cannot open Office document.");
            try(ZipInputStream zip=new ZipInputStream(stream)){
                ZipEntry entry;
                byte[] buf=new byte[8192];
                while((entry=zip.getNextEntry())!=null){
                    if(++count>3000)throw new IOException("Too many Office package entries.");
                    String name=entry.getName();
                    if(entry.isDirectory()){zip.closeEntry();continue;}
                    if(name.contains("..") || name.startsWith("/"))throw new IOException("Unsafe document package.");
                    ByteArrayOutputStream memory=new ByteArrayOutputStream();
                    int n;
                    while((n=zip.read(buf))!=-1){
                        total+=n;
                        if(total>MAX_ARCHIVE_BYTES)throw new IOException("Document too large to process offline.");
                        memory.write(buf,0,n);
                    }
                    result.put(name,memory.toByteArray());
                    zip.closeEntry();
                }
            }
        }
        return result;
    }

    private static Document xml(byte[] bytes) throws IOException {
        if(bytes==null)throw new IOException("Missing required Office XML document.");
        try {
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch(Exception e) {
            throw new IOException("Invalid or unsupported Office document.",e);
        }
    }

    static final class Section {
        final String text;
        final boolean heading;
        Section(String t,boolean h){ text=t;heading=h; }
    }

    private static List<Section> officeText(Map<String,byte[]> archive,Action action) throws IOException {
        if(action==Action.DOCX_PDF || action==Action.DOCX_TXT) return docxText(archive);
        if(action==Action.XLSX_PDF)return csvSegments(parseXlsx(archive));
        if(action==Action.PPTX_PDF)return pptxText(archive);
        throw new IOException("Unsupported source format.");
    }

    private static List<Section> docxText(Map<String,byte[]> archive) throws IOException {
        Document doc=xml(archive.get("word/document.xml"));
        NodeList bodies=doc.getElementsByTagNameNS(W,"body");
        if(bodies.getLength()==0)throw new IOException("Missing Word document body.");
        ArrayList<Section> out=new ArrayList<>();
        Node body=bodies.item(0);
        for(Node child=body.getFirstChild();child!=null;child=child.getNextSibling()){
            if(is(child,W,"p")) {
                String text=allText((Element)child,W,"t");
                String style="";
                NodeList styles=((Element)child).getElementsByTagNameNS(W,"pStyle");
                if(styles.getLength()>0)style=((Element)styles.item(0)).getAttributeNS(W,"val");
                out.add(new Section(text,style.toLowerCase(Locale.ROOT).startsWith("heading")
                    || style.toLowerCase(Locale.ROOT).startsWith("title")));
            } else if(is(child,W,"tbl")){
                NodeList rows=((Element)child).getElementsByTagNameNS(W,"tr");
                for(int i=0;i<rows.getLength();i++){
                    NodeList cells=((Element)rows.item(i)).getElementsByTagNameNS(W,"tc");
                    ArrayList<String> values=new ArrayList<>();
                    for(int j=0;j<cells.getLength() && j<18;j++)
                        values.add(allText((Element)cells.item(j),W,"t"));
                    out.add(new Section(joinRow(values),false));
                }
                out.add(new Section("",false));
            }
            if(out.size()>30000)throw new IOException("Document contains too many paragraphs.");
        }
        return out;
    }

    private static List<Section> pptxText(Map<String,byte[]> archive) throws IOException {
        List<String> slides = new ArrayList<>();
        for(String file:archive.keySet()){
            if(file.matches("ppt/slides/slide[0-9]+\\.xml"))slides.add(file);
        }
        if(slides.isEmpty())throw new IOException("Presentation contains no supported slides.");
        slides.sort(Comparator.comparingInt(OfficeEngine::slideNumber));
        List<Section> out=new ArrayList<>();
        int slideNo=1;
        for(String path:slides){
            out.add(new Section("Slide "+slideNo++,true));
            Document doc=xml(archive.get(path));
            NodeList paragraphs=doc.getElementsByTagNameNS(A,"p");
            for(int j=0;j<paragraphs.getLength();j++){
                String val=allText((Element)paragraphs.item(j),A,"t");
                if(!val.isEmpty())out.add(new Section(val,false));
            }
            out.add(new Section("",false));
        }
        return out;
    }

    private static int slideNumber(String path) {
        String number=path.replaceAll("[^0-9]","");
        try{return Integer.parseInt(number);}catch(Exception e){return Integer.MAX_VALUE;}
    }

    private static String allText(Element element,String ns,String tag){
        NodeList nodes=element.getElementsByTagNameNS(ns,tag);
        StringBuilder value=new StringBuilder();
        for(int k=0;k<nodes.getLength();k++)value.append(nodes.item(k).getTextContent());
        return value.toString();
    }
    private static boolean is(Node node,String namespace,String local){
        return node.getNodeType()==Node.ELEMENT_NODE && namespace.equals(node.getNamespaceURI())
            && local.equals(node.getLocalName());
    }

    private static List<List<String>> parseXlsx(Map<String,byte[]> archive) throws IOException {
        List<String> strings = new ArrayList<>();
        if(archive.containsKey("xl/sharedStrings.xml")){
            Document shared=xml(archive.get("xl/sharedStrings.xml"));
            NodeList entries=shared.getElementsByTagNameNS(S,"si");
            for(int i=0;i<entries.getLength();i++)
                strings.add(allText((Element)entries.item(i),S,"t"));
        }
        List<String> sheets=new ArrayList<>();
        for(String key:archive.keySet())
            if(key.matches("xl/worksheets/sheet[0-9]+\\.xml"))sheets.add(key);
        if(sheets.isEmpty())throw new IOException("No spreadsheet worksheet found.");
        sheets.sort(Comparator.comparingInt(OfficeEngine::slideNumber));
        Document doc=xml(archive.get(sheets.get(0)));
        NodeList rows=doc.getElementsByTagNameNS(S,"row");
        List<List<String>> result=new ArrayList<>();
        for(int i=0;i<rows.getLength() && i<15000;i++){
            Element row=(Element)rows.item(i);
            NodeList cells=row.getElementsByTagNameNS(S,"c");
            ArrayList<String> values=new ArrayList<>();
            for(int j=0;j<cells.getLength() && j<256;j++){
                Element cell=(Element)cells.item(j);
                int column=Math.min(255,columnIndex(cell.getAttribute("r")));
                if(column>=0)while(values.size()<column)values.add("");
                String type=cell.getAttribute("t");
                String value=allText(cell,S,"v");
                if("inlineStr".equals(type))value=allText(cell,S,"t");
                else if("s".equals(type)){
                    try{
                        int index=Integer.parseInt(value);
                        value=index>=0&&index<strings.size()?strings.get(index):"";
                    }catch(Exception ignored){value="";}
                } else if("b".equals(type))value="1".equals(value)?"TRUE":"FALSE";
                if(values.size()<256)values.add(value);
            }
            result.add(values);
        }
        return result;
    }

    private static int columnIndex(String reference) {
        int c=0,k=0;
        while(k<reference.length() && Character.isLetter(reference.charAt(k))){
            c=c*26+(Character.toUpperCase(reference.charAt(k))-'A'+1);
            k++;
        }
        return k==0?-1:c-1;
    }

    private static List<Section> csvSegments(List<List<String>> rows){
        List<Section> out=new ArrayList<>();
        int i=0;
        for(List<String> row:rows){
            out.add(new Section(joinRow(row),i++==0));
        }
        return out;
    }
    private static String joinRow(List<String> row) {
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<row.size();i++){
            if(i>0)sb.append("   |   ");
            String value=row.get(i).replaceAll("[\\r\\n]+"," ");
            if(value.length()>160)value=value.substring(0,160)+"…";
            sb.append(value);
        }
        return sb.toString();
    }
    private static String join(List<Section> sections){
        StringBuilder out=new StringBuilder();
        for(Section section:sections){
            if(out.length()+section.text.length()>MAX_TEXT_CHARS)break;
            out.append(section.text).append('\n');
        }
        return out.toString();
    }

    /** Lightweight A4 typesetting for simple documents; page layout is approximate. */
    private static void renderPdf(List<Section> segments,OutputStream output) throws IOException {
        PdfDocument pdf=new PdfDocument();
        Paint normal=new Paint(Paint.ANTI_ALIAS_FLAG);
        normal.setColor(Color.rgb(42,43,55));
        normal.setTextSize(11.5f);
        normal.setTypeface(android.graphics.Typeface.create("sans-serif",0));
        Paint header=new Paint(Paint.ANTI_ALIAS_FLAG);
        header.setColor(Color.rgb(55,47,115));
        header.setTextSize(17);
        header.setTypeface(android.graphics.Typeface.create("sans-serif",1));
        Canvas canvas=null;
        PdfDocument.Page page=null;
        int pageNumber=0;
        float y=50;
        final float left=35,right=35,maximum=595-left-right;
        try {
            if(segments.isEmpty())segments.add(new Section("",false));
            for(Section section:segments){
                Paint paint=section.heading?header:normal;
                String s=section.text==null?"":section.text.replace('\t',' ');
                if(s.isEmpty()){
                    if(page==null){
                        page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNumber).create());
                        canvas=page.getCanvas();
                        canvas.drawColor(Color.WHITE);
                    }
                    y+=12;
                    continue;
                }
                int offset=0;
                while(offset<s.length()){
                    if(page==null || y>790){
                        if(page!=null)pdf.finishPage(page);
                        page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,++pageNumber).create());
                        canvas=page.getCanvas();
                        canvas.drawColor(Color.WHITE);
                        y=50;
                    }
                    int chars=Math.max(1,paint.breakText(s,offset,s.length(),true,maximum,null));
                    int end=Math.min(s.length(),offset+chars);
                    if(end<s.length()){
                        int space=s.lastIndexOf(' ',end-1);
                        if(space>offset+Math.max(2,chars/3))end=space+1;
                    }
                    canvas.drawText(s,offset,end,left,y,paint);
                    y+=section.heading?25:17;
                    offset=end;
                }
                y+=section.heading?9:5;
            }
            if(page==null){
                page=pdf.startPage(new PdfDocument.PageInfo.Builder(595,842,1).create());
                page.getCanvas().drawColor(Color.WHITE);
            }
            pdf.finishPage(page);
            pdf.writeTo(output);
        } finally {pdf.close();}
    }

    /** Produce a standards-based, editable DOCX containing extracted PDF text. */
    private static void writeDocx(String text,OutputStream output) throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(new NonClosingOutputStream(output))){
            put(zip,"[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                +"<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                +"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                +"<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                +"<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                +"</Types>");
            put(zip,"_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                +"</Relationships>");
            StringBuilder body=new StringBuilder();
            for(String line:text.split("\\R",-1)){
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(escapeXml(line)).append("</w:t></w:r></w:p>");
            }
            body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>");
            put(zip,"word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                +"<w:document xmlns:w=\""+W+"\"><w:body>"+body+"</w:body></w:document>");
            zip.finish();
        }
    }

    private static List<List<String>> readCsv(Context context,Uri uri) throws IOException {
        List<Section> lines=plainText(context,uri);
        // Rejoin with newline so quoted cells can contain line breaks.
        String source=join(lines);
        if(source.startsWith("\uFEFF"))source=source.substring(1);
        char delim=source.indexOf(';')>=0 && source.indexOf(',')<0?';':',';
        List<List<String>> rows=new ArrayList<>();
        List<String> row=new ArrayList<>();
        StringBuilder cell=new StringBuilder();
        boolean quote=false;
        for(int i=0;i<source.length();i++){
            char ch=source.charAt(i);
            if(ch=='"'){
                if(quote && i+1<source.length() && source.charAt(i+1)=='"'){cell.append('"');i++;}
                else quote=!quote;
            }else if(ch==delim&&!quote){row.add(cell.toString());cell.setLength(0);}
            else if((ch=='\r'||ch=='\n')&&!quote){
                if(ch=='\r' && i+1<source.length() && source.charAt(i+1)=='\n')i++;
                row.add(cell.toString());cell.setLength(0);
                if(row.size()>256)throw new IOException("Too many CSV columns.");
                rows.add(row);row=new ArrayList<>();
                if(rows.size()>15000)throw new IOException("Too many CSV rows.");
            }else cell.append(ch);
        }
        if(cell.length()>0||!row.isEmpty()){row.add(cell.toString());rows.add(row);}
        return rows;
    }

    private static void writeCsv(List<List<String>> rows,OutputStream out) throws IOException {
        for(List<String> row:rows){
            StringBuilder line=new StringBuilder();
            for(int i=0;i<row.size();i++){
                if(i>0)line.append(',');
                String item=row.get(i);
                if(item.indexOf(',')>=0||item.indexOf('"')>=0||item.indexOf('\n')>=0)
                    line.append('"').append(item.replace("\"","\"\"")).append('"');
                else line.append(item);
            }
            line.append("\r\n");
            out.write(line.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeXlsx(List<List<String>> rows,OutputStream out) throws IOException {
        try(ZipOutputStream zip=new ZipOutputStream(new NonClosingOutputStream(out))){
            put(zip,"[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                +"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                +"<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                +"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                +"<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                +"</Types>");
            put(zip,"_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                +"</Relationships>");
            put(zip,"xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<workbook xmlns=\""+S+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                +"<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip,"xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                +"</Relationships>");
            StringBuilder sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\""+S+"\"><sheetData>");
            for(int row=0;row<rows.size() && row<15000;row++){
                sheet.append("<row r=\"").append(row+1).append("\">");
                List<String> values=rows.get(row);
                for(int col=0;col<values.size()&&col<256;col++){
                    String value=values.get(col);
                    if(value.isEmpty())continue;
                    sheet.append("<c r=\"").append(colName(col)).append(row+1)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escapeXml(value)).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            put(zip,"xl/worksheets/sheet1.xml",sheet.toString());
            zip.finish();
        }
    }

    private static String colName(int number){
        StringBuilder result=new StringBuilder();
        do{result.insert(0,(char)('A'+number%26));number=number/26-1;}while(number>=0);
        return result.toString();
    }

    private static String escapeXml(String s){
        StringBuilder result=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            switch(c){
                case '&':result.append("&amp;");break;
                case '<':result.append("&lt;");break;
                case '>':result.append("&gt;");break;
                case '"':result.append("&quot;");break;
                case '\'':result.append("&apos;");break;
                default:
                    if(c==9||c==10||c==13||c>=32)result.append(c);
            }
        }
        return result.toString();
    }

    private static void put(ZipOutputStream zip,String name,String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream destination;
        NonClosingOutputStream(OutputStream stream){destination=stream;}
        @Override public void write(int b)throws IOException{destination.write(b);}
        @Override public void write(byte[] b,int o,int l)throws IOException{destination.write(b,o,l);}
        @Override public void flush()throws IOException{destination.flush();}
        @Override public void close()throws IOException{destination.flush();}
    }
}
