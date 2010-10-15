/**
 * JLibs: Common Utilities for Java
 * Copyright (C) 2009  Santhosh Kumar T <santhosh.tekuri@gmail.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 */

package jlibs.xml.sax.async;

import jlibs.nbp.Chars;
import jlibs.nbp.NBHandler;
import jlibs.xml.sax.AbstractXMLReader;
import org.apache.xerces.util.XMLChar;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.Locator2;

import java.io.CharArrayReader;
import java.io.IOException;
import java.util.*;

/**
 * @author Santhosh Kumar T
 */
@SuppressWarnings({"ThrowableInstanceNeverThrown"})
public class AsyncXMLReader extends AbstractXMLReader implements NBHandler<SAXException>, Locator2{
    private static Map<String, char[]> defaultEntities = new HashMap<String, char[]>();
    static{
        defaultEntities.put("amp",  new char[]{ '&' });
        defaultEntities.put("lt",   new char[]{ '<' });
        defaultEntities.put("gt",   new char[]{ '>' });
        defaultEntities.put("apos", new char[]{ '\'' });
        defaultEntities.put("quot", new char[]{ '"' });
    }

    private XMLEntityScanner xmlScanner = new XMLEntityScanner(this, XMLScanner.RULE_DOCUMENT);
    private XMLFeeder feeder;
    void setFeeder(XMLFeeder feeder){
        if(this.feeder.getParent()==feeder){
            if(this.feeder.postAction!=null)
                this.feeder.postAction.run();
        }
        this.feeder = feeder;
    }
    
    @Override
    public void setFeature(String name, boolean value) throws SAXNotRecognizedException{
        try{
            super.setFeature(name, value);
        } catch(SAXNotRecognizedException e){
            // ignore
        }
    }

    public XMLFeeder createFeeder(InputSource inputSource) throws IOException, SAXException{
        xmlScanner.reset();
        feeder=new XMLFeeder(this, xmlScanner, inputSource);
        documentStart();
        return feeder;
    }

    @Override
    public void parse(InputSource input) throws IOException, SAXException{
        createFeeder(input).feed();
    }

    @Override
    public void parse(String systemID) throws IOException, SAXException{
        createFeeder(new InputSource(systemID)).feed();
    }

    /*-------------------------------------------------[ Locator ]---------------------------------------------------*/

    @Override
    public String getPublicId(){
        return feeder.publicID;
    }

    @Override
    public String getSystemId(){
        return feeder.systemID;
    }

    @Override
    public int getLineNumber(){
        return feeder.parser.location.getLineNumber();
    }

    @Override
    public int getColumnNumber(){
        return feeder.parser.location.getColumnNumber();
    }

    @Override
    public String getXMLVersion(){
        return "1.0";
    }

    @Override
    public String getEncoding(){
        return feeder.getCharset().name(); // todo: return declared encoding
    }

    /*-------------------------------------------------[ Document ]---------------------------------------------------*/

    void documentStart() throws SAXException{
        encoding = null;
        standalone = null;
        xdeclEnd = false;
        curQName.reset();
        value.setLength(0);
        valueStarted = false;
        entityValue = false;

        namespaces.reset();
        attributes.reset();
        elements.reset();

        externalDTDPublicID = null;
        externalDTDSystemID = null;
        piTarget = null;
        systemID = null;
        publicID = null;
        notationName = null;

        entityName = null;
        entities.clear();
        entityStack.clear();

        paramEntityName = null;
        paramEntities.clear();
        paramEntityStack.clear();
        peReferenceOutsideMarkup = false;

        dtd.reset();
        dtdElementName = null;
        dtdElement = null;
        attributeList = null;
        dtdAttribute = null;

        handler.setDocumentLocator(this);
        handler.startDocument();
    }

    /*-------------------------------------------------[ XML Decleration ]---------------------------------------------------*/

    void version(Chars data) throws SAXException{
        if(!"1.0".contentEquals(data))
            fatalError("Unsupported XML Version: "+data);
    }

    String encoding;
    void encoding(Chars data) throws SAXException{
        encoding = data.toString();
    }

    private Boolean standalone;
    void standalone(Chars data){
        standalone = "yes".contentEquals(data);
    }

    boolean xdeclEnd;
    void xdeclEnd(){
        feeder.setDeclaredEncoding(encoding);
        encoding = null;
        xdeclEnd = true;
    }

    /*-------------------------------------------------[ QName ]---------------------------------------------------*/

    private QName curQName = new QName();
    void prefix(Chars data){
        curQName.prefixLength = data.length();
    }

    void localName(Chars data){}

    void qname(Chars data){
        curQName.setName(data.toString());
    }

    /*-------------------------------------------------[ Value ]---------------------------------------------------*/

    private StringBuilder value = new StringBuilder();
    private boolean valueStarted = true;
    private boolean entityValue = false;

    void valueStart(){
        value.setLength(0);
        valueStarted = true;
        entityValue = false;
    }

    void entityValue(){
        entityValue = true;
    }

    void discard(Chars data){}
    
    void rawValue(Chars data){
        if(entityValue)
            value.append(data);
        else{
            char[] chars = data.array();
            int end = data.offset() + data.length();
            for(int i=data.offset(); i<end; i++){
                char ch = chars[i];
                if(ch=='\n' || ch=='\r' || ch=='\t')
                    ch = ' ';
                value.append(ch);
            }
        }
    }

    void hexCode(Chars data) throws SAXException{
        codePoint(data, 16);
    }

    void asciiCode(Chars data) throws SAXException{
        codePoint(data, 10);
    }

    private void codePoint(Chars data, int radix) throws SAXException{
        int cp = Integer.parseInt(data.toString(), radix);
        if(XMLChar.isValid(cp)){
            if(valueStarted)
                value.appendCodePoint(cp);
            else{
                char chars[] = Character.toChars(cp);
                handler.characters(chars, 0, chars.length);
            }
        }else
            fatalError("invalid xml character");
    }

    private ArrayDeque<String> entityStack = new ArrayDeque<String>();
    @SuppressWarnings({"ConstantConditions"})
    void entityReference(Chars data) throws SAXException, IOException{
        if(entityValue){
            value.append('&').append(data).append(';');
            return;
        }
        
        String entity = data.toString();

        char[] entityContent = defaultEntities.get(entity);
        if(entityContent!=null){
            if(valueStarted)
                value.append(entityContent);
            else
                handler.characters(entityContent, 0, entityContent.length);
        }else{
            EntityValue entityValue = entities.get(entity);
            if(entityValue==null)
                fatalError("The entity \""+entity+"\" was referenced, but not declared.");

            if(entityValue.unparsed)
                fatalError("The unparsed entity reference \"&"+entity+";\" is not permitted");
            
            if(standalone==Boolean.TRUE && entityValue.externalDefinition)
                fatalError("The external entity reference \"&"+entity+";\" is not permitted in standalone document");

            if(standalone==Boolean.TRUE && entityValue.externalValue)
                fatalError("The reference to entity \""+entity+"\" declared in an external parsed entity is not permitted in a standalone document");

            checkRecursion(entityStack, entity, "entity");

            int rule;
            if(valueStarted){
                if(entityValue.externalValue)
                    fatalError("The external entity reference \"&"+entityName+";\" is not permitted in an attribute value.");
                rule = XMLScanner.RULE_INT_VALUE;
            }else{
                rule = entityValue.externalValue ? XMLScanner.RULE_EXT_ELEM_CONTENT : XMLScanner.RULE_INT_ELEM_CONTENT;
            }

            entityStack.push(entity);
            try{
                entityValue.parse(rule, valueStarted).postAction = new Runnable(){
                    @Override
                    public void run(){
                        entityStack.pop();
                    }
                };
            }catch(IOException ex){
                throw new RuntimeException(ex);
            }
        }
    }

    private ArrayDeque<String> paramEntityStack = new ArrayDeque<String>();

    private boolean peReferenceOutsideMarkup = false;
    void peReferenceOutsideMarkup(){
        peReferenceOutsideMarkup = true;
    }

    @SuppressWarnings({"ConstantConditions"})
    void peReference(Chars data) throws Exception{
        String param = data.toString();
        final EntityValue entityValue = paramEntities.get(param);

        if(entityValue==null)
            fatalError("The param entity \""+param+"\" was referenced, but not declared.");

        if(standalone==Boolean.TRUE && entityValue.externalValue)
            fatalError("The reference to param entity \""+param+"\" declared in an external parsed entity is not permitted in a standalone document");

        checkRecursion(paramEntityStack, param, "parameter entity");

        if(valueStarted){
            if(feeder.parser==xmlScanner && feeder.getParent()==null)
                fatalError("The parameter entity reference \"%"+data+";\" cannot occur within markup in the internal subset of the DTD.");

            if(entityValue.content!=null)
                value.append(entityValue.content);
            else{
                entityValue.getContent().postAction = new Runnable(){
                    @Override
                    public void run(){
                        entityValue.content = externalEntityValue;
                        value.append(externalEntityValue);
                    }
                };
            }
        }else{
            if(peReferenceOutsideMarkup){
                peReferenceOutsideMarkup = false;
                paramEntityStack.push(param);
                try{
                    entityValue.parse(entityValue.externalValue ? XMLScanner.RULE_EXT_SUBSET : XMLScanner.RULE_EXT_SUBSET_DECL, false).postAction =  new Runnable(){
                        @Override
                        public void run(){
                            paramEntityStack.pop();
                        }
                    };
                }catch(IOException ex){
                    throw new RuntimeException(ex);
                }
            }else{
                if(feeder.parser==xmlScanner && feeder.getParent()==null)
                    fatalError("The parameter entity reference \"%"+data+";\" cannot occur within markup in the internal subset of the DTD.");

                if(entityValue.content!=null){
                    feeder.setChild(new XMLFeeder(this, feeder.parser, new CharReader(feeder, entityValue.content)));
                }else{
                    entityValue.getContent().postAction = new Runnable(){
                        @Override
                        public void run(){
                            entityValue.content = externalEntityValue;
                            try{
                                feeder.setChild(new XMLFeeder(AsyncXMLReader.this, feeder.parser, new CharReader((XMLFeeder)feeder.getParent(), externalEntityValue)));
                            } catch(Exception ex){
                                throw new RuntimeException(ex);
                            }
                        }
                    };
                }
            }
        }
    }

    void valueEnd(){
        valueStarted = false;
        entityValue = false;
    }

    /*-------------------------------------------------[ Start Element ]---------------------------------------------------*/

    private final Namespaces namespaces = new Namespaces(handler);
    private final DTD dtd = new DTD(namespaces);
    private final Attributes attributes = new Attributes(namespaces, dtd);
    private final Elements elements = new Elements(handler, namespaces, attributes);

    void attributesStart(){
        elements.push1(curQName);
    }

    void attributeEnd() throws SAXException{
        String error = attributes.addAttribute(elements.currentElementName(), curQName, value);
        if(error!=null)
            fatalError(error);
        curQName.reset();
    }

    void attributesEnd() throws SAXException{
        String error = elements.push2();
        if(error!=null)
            fatalError(error);
    }

    void emptyElementEnd() throws SAXException{
        elements.pop();
    }

    void elementEnd() throws SAXException{
        String error = elements.pop(curQName.name);
        if(error!=null)
            fatalError(error);
        curQName.reset();
    }

    /*-------------------------------------------------[ PI ]---------------------------------------------------*/

    private String piTarget;
    void piTarget(Chars data){
        piTarget = data.toString();
    }

    void piData(Chars piData) throws SAXException{
        handler.processingInstruction(piTarget, piData.length()>0 ? piData.toString() : "");
    }

    void piData() throws SAXException{
        handler.processingInstruction(piTarget, "");
    }

    /*-------------------------------------------------[ Misc ]---------------------------------------------------*/

    private boolean isWhitespace(Chars data){
        char chars[] = data.array();
        int end = data.offset()+data.length();
        for(int i=data.offset(); i<end; i++){
            if(!XMLChar.isSpace(chars[i]))
                return false;
        }
        return true;
    }
    
    void characters(Chars data) throws SAXException{
        if(dtd.nonMixedElements.contains(elements.currentElementName()) && isWhitespace(data))
            handler.ignorableWhitespace(data.array(), data.offset(), data.length());
        else
            handler.characters(data.array(), data.offset(), data.length());
    }

    void cdata(Chars data) throws SAXException{
        handler.startCDATA();
        handler.characters(data.array(), data.offset(), data.length());
        handler.endCDATA();
    }

    void comment(Chars data) throws SAXException{
        handler.comment(data.array(), data.offset(), data.length());
    }

    @Override
    public void fatalError(String message) throws SAXException{
        fatalError(new SAXParseException(message, this));
    }

    public void fatalError(SAXParseException ex) throws SAXException{
        try{
            handler.fatalError(ex);
            throw ex;
        }finally{
            handler.endDocument();
        }
    }

    @Override
    public void onSuccessful() throws SAXException{
        if(feeder.getParent()==null)
            handler.endDocument();
    }

    /*-------------------------------------------------[ DTD ]---------------------------------------------------*/

    void dtdRoot(Chars data){
        dtd.root = data.toString();
    }

    private String systemID;
    void systemID(Chars data){
        systemID = data.toString();
    }

    private String publicID;
    void publicID(Chars data){
        publicID = AttributeType.toPublicID(data.toString());
    }

    void dtdStart() throws SAXException{
        handler.startDTD(dtd.root, publicID, systemID);
        externalDTDPublicID = publicID;
        externalDTDSystemID = systemID;
        publicID = systemID = null;
    }

    private String notationName;
    void notationName(Chars data){
        notationName = data.toString();
    }

    void notationEnd() throws SAXException, IOException{
        systemID = feeder.resolve(systemID);
        handler.notationDecl(notationName, publicID, systemID);
        notationName = null;
        publicID = this.systemID = null;
    }

    private String dtdElement;
    void dtdElement(Chars data){
        dtdElement = data.toString();
    }

    void notMixed(){
        dtd.nonMixedElements.add(dtdElement);
    }

    public void dtdEnd() throws SAXException, IOException{
        if(externalDTDPublicID!=null || externalDTDSystemID!=null){
            externalDTDSystemID = feeder.resolve(externalDTDSystemID);
            resolveExternalDTD();
        }
        handler.endDTD();
    }

    /*-------------------------------------------------[ Entity Definition ]---------------------------------------------------*/

    private String entityName;
    void entityName(Chars data){
        entityName = data.toString();
    }

    private boolean unparsedEntity;
    void notationReference(Chars data) throws IOException, SAXException{
        handler.unparsedEntityDecl(entityName, publicID, feeder.resolve(systemID) , data.toString());
        unparsedEntity = true;
    }

    private Map<String, EntityValue> entities = new HashMap<String, EntityValue>();
    void entityEnd() throws SAXException, IOException{
        // entities may be declared more than once, with the first declaration being the binding one        
        if(!entities.containsKey(entityName))
            entities.put(entityName, new EntityValue());
        unparsedEntity = false;
        value.setLength(0);
        publicID = systemID = null;
    }

    private char[] externalEntityValue;
    void externalEntityValue(Chars data){
        externalEntityValue = Arrays.copyOfRange(data.array(), data.offset(), data.offset()+data.length());
    }

    class EntityValue{
        String entityName;
        char[] content;
        boolean externalDefinition;
        boolean unparsed;
        boolean externalValue;
        InputSource inputSource;

        public EntityValue() throws IOException, SAXException{
            entityName = AsyncXMLReader.this.entityName;
            externalDefinition = feeder.getParent()!=null;
            unparsed = unparsedEntity;

            if(systemID==null && publicID==null)
                content = value.toString().toCharArray();
            else{
                externalValue = true;
                inputSource = feeder.resolve(publicID, systemID);
            }
        }

        public XMLFeeder getContent() throws IOException, SAXException{
            InputSource is = handler.resolveEntity(inputSource.getPublicId(), inputSource.getSystemId());
            if(is==null)
                is = inputSource;

            XMLEntityScanner scanner = new XMLEntityScanner(AsyncXMLReader.this, XMLScanner.RULE_EXTERNAL_ENTITY_VALUE);
            XMLFeeder childFeeder = new XMLFeeder(AsyncXMLReader.this, scanner, is);
            feeder.setChild(childFeeder);
            return childFeeder;
        }

        public XMLFeeder parse(int rule, boolean attributeValue) throws IOException, SAXException{
            if(inputSource==null){
                XMLScanner scanner;
                if(attributeValue){
                    scanner = new XMLScanner(AsyncXMLReader.this, rule){
                        @Override
                        public void consume(char ch) throws IOException{
                            if(ch=='\n' || ch=='\r' || ch=='\t')
                                ch = ' ';
                            super.consume(ch);
                        }
                    };
                }else
                    scanner = new XMLScanner(AsyncXMLReader.this, rule);
                InputSource is = new InputSource(getSystemId());
                is.setPublicId(getPublicId());
                is.setCharacterStream(new CharArrayReader(content));
                XMLFeeder childFeeder = new XMLFeeder(AsyncXMLReader.this, scanner, is);
                feeder.setChild(childFeeder);
                return childFeeder;
            }else{
                InputSource is = handler.resolveEntity(inputSource.getPublicId(), inputSource.getSystemId());
                XMLEntityScanner scanner = new XMLEntityScanner(AsyncXMLReader.this, rule);
                XMLFeeder childFeeder = new XMLFeeder(AsyncXMLReader.this, scanner, is == null ? inputSource : is);
                feeder.setChild(childFeeder);
                return childFeeder;
            }
        }
    }

    public void checkRecursion(Deque<String> stack, String current, String type) throws SAXException{
        if(stack.contains(current)){
            StringBuilder message = new StringBuilder("Recursive ").append(type).append(" reference ");
            message.append('"').append(current).append('"').append(". (Reference path: ");
            boolean first = true;
            Iterator<String> iter = stack.descendingIterator();
            while(iter.hasNext()){
                if(first)
                    first = false;
                else
                    message.append(" -> ");
                message.append(iter.next());
            }
            message.append(" -> ").append(current);
            message.append(')');
            fatalError(message.toString());
        }
    }

    /*-------------------------------------------------[ Param Entity Definition ]---------------------------------------------------*/

    private String paramEntityName;
    void paramEntityName(Chars data){
        paramEntityName = data.toString();
    }

    private Map<String, EntityValue> paramEntities = new HashMap<String, EntityValue>();
    void paramEntityEnd() throws SAXException, IOException{
        if(!paramEntities.containsKey(paramEntityName))
            paramEntities.put(paramEntityName, new EntityValue());
        unparsedEntity = false;
        value.setLength(0);
        publicID = systemID = null;
    }

    /*-------------------------------------------------[ DTD Attributes ]---------------------------------------------------*/

    private String dtdElementName;
    private Map<String, DTDAttribute> attributeList;
    private DTDAttribute dtdAttribute;

    void dtdAttributesStart(Chars data){
        dtdElementName = data.toString();
        attributeList = dtd.attributes.get(dtdElementName);
        if(attributeList==null)
            dtd.attributes.put(dtdElementName, attributeList=new HashMap<String, DTDAttribute>());
    }

    void dtdAttribute(Chars data){
        String attributeName = data.toString();
        if(attributeList.get(attributeName)==null){
            dtdAttribute = new DTDAttribute();
            dtdAttribute.name = attributeName;
            attributeList.put(attributeName, dtdAttribute);
        }else
            dtdAttribute = null;
    }

    void cdataAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.CDATA;
    }

    void idAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.ID;
    }

    void idRefAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.IDREF;
    }

    void idRefsAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.IDREFS;
    }

    void nmtokenAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.NMTOKEN;
    }

    void nmtokensAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.NMTOKENS;
    }

    void entityAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.ENTITY;
    }

    void entitiesAttribute(){
        if(dtdAttribute!=null)
            dtdAttribute.type = AttributeType.ENTITIES;
    }

    void enumerationAttribute(){
        if(dtdAttribute!=null){
            dtdAttribute.type = AttributeType.ENUMERATION;
            dtdAttribute.validValues = new ArrayList<String>();
        }
    }

    void notationAttribute(){
        if(dtdAttribute!=null){
            dtdAttribute.type = AttributeType.NOTATION;
            dtdAttribute.validValues = new ArrayList<String>();
        }
    }

    void attributeEnumValue(Chars data){
        if(dtdAttribute!=null)
            dtdAttribute.validValues.add(data.toString());
    }

    void attributeNotationValue(Chars data){
        if(dtdAttribute!=null)
            dtdAttribute.validValues.add(data.toString());
    }

    void attributeDefaultValue() throws SAXException{
        if(dtdAttribute!=null){
            dtdAttribute.valueType = AttributeValueType.DEFAULT;
            dtdAttribute.value = dtdAttribute.type.normalize(value.toString());
            dtdAttribute.fire(handler);
        }
        value.setLength(0);
    }

    void attributeRequired() throws SAXException{
        if(dtdAttribute!=null){
            dtdAttribute.valueType = AttributeValueType.REQUIRED;
            dtdAttribute.fire(handler);
        }
    }

    void attributeImplied() throws SAXException{
        if(dtdAttribute!=null){
            dtdAttribute.valueType = AttributeValueType.IMPLIED;
            dtdAttribute.fire(handler);
        }
    }

    void attributeFixedValue() throws SAXException{
        if(dtdAttribute!=null){
            dtdAttribute.valueType = AttributeValueType.FIXED;
            dtdAttribute.value = dtdAttribute.type.normalize(value.toString());
            dtdAttribute.fire(handler);
        }
        value.setLength(0);
    }

    void dtdAttributesEnd(){}

    /*-------------------------------------------------[ Entity Resolution ]---------------------------------------------------*/

    private String externalDTDPublicID, externalDTDSystemID;    
    private void resolveExternalDTD() throws IOException, SAXException{
        String publicID = externalDTDPublicID;
        String systemID = this.externalDTDSystemID;
        this.externalDTDPublicID = this.externalDTDSystemID = null;
        InputSource is = handler.resolveEntity(publicID, systemID);
        XMLEntityScanner dtdScanner = new XMLEntityScanner(this, XMLScanner.RULE_EXT_SUBSET);
        if(is==null){
            is = new InputSource(systemID);
            is.setPublicId(publicID);
        }
        encoding = null;
        feeder.setChild(new XMLFeeder(this, dtdScanner, is));
    }
}