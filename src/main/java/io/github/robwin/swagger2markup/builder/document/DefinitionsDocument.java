/*
 *
 *  Copyright 2015 Robert Winkler
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.robwin.swagger2markup.builder.document;

import com.google.common.collect.ImmutableMap;
import io.github.robwin.markup.builder.MarkupDocBuilder;
import io.github.robwin.markup.builder.MarkupDocBuilders;
import io.github.robwin.swagger2markup.config.Swagger2MarkupConfig;
import io.github.robwin.swagger2markup.type.ObjectType;
import io.github.robwin.swagger2markup.type.Type;
import io.swagger.models.ComposedModel;
import io.swagger.models.Model;
import io.swagger.models.RefModel;
import io.swagger.models.properties.Property;
import io.swagger.models.refs.RefFormat;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.Validate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * @author Robert Winkler
 */
public class DefinitionsDocument extends MarkupDocument {

    private final String DEFINITIONS;
    private static final List<String> IGNORED_DEFINITIONS = Collections.singletonList("Void");
    private final String JSON_SCHEMA;
    private final String XML_SCHEMA;
    private static final String JSON_SCHEMA_EXTENSION = ".json";
    private static final String XML_SCHEMA_EXTENSION = ".xsd";
    private static final String JSON = "json";
    private static final String XML = "xml";
    private static final String DESCRIPTION_FOLDER_NAME = "definitions";
    private static final String DESCRIPTION_FILE_NAME = "description";
    private boolean schemasEnabled;
    private String schemasFolderPath;
    private boolean handWrittenDescriptionsEnabled;
    private String descriptionsFolderPath;
    private final int inlineSchemaDepthLevel;
    private final Comparator<String> definitionOrdering;

    public DefinitionsDocument(Swagger2MarkupConfig swagger2MarkupConfig, String outputDirectory){
        super(swagger2MarkupConfig, outputDirectory);

        ResourceBundle labels = ResourceBundle.getBundle("lang/labels",
                swagger2MarkupConfig.getOutputLanguage().toLocale());
        DEFINITIONS = labels.getString("definitions");
        JSON_SCHEMA = labels.getString("json_schema");
        XML_SCHEMA = labels.getString("xml_schema");

        this.inlineSchemaDepthLevel = swagger2MarkupConfig.getInlineSchemaDepthLevel();
        if(isNotBlank(swagger2MarkupConfig.getSchemasFolderPath())){
            this.schemasEnabled = true;
            this.schemasFolderPath = swagger2MarkupConfig.getSchemasFolderPath();
        }
        if(isNotBlank(swagger2MarkupConfig.getDescriptionsFolderPath())){
            this.handWrittenDescriptionsEnabled = true;
            this.descriptionsFolderPath = swagger2MarkupConfig.getDescriptionsFolderPath() + "/" + DESCRIPTION_FOLDER_NAME;
        }
        if(schemasEnabled){
            if (logger.isDebugEnabled()) {
                logger.debug("Include schemas is enabled.");
            }
        }else{
            if (logger.isDebugEnabled()) {
                logger.debug("Include schemas is disabled.");
            }
        }
        if(handWrittenDescriptionsEnabled){
            if (logger.isDebugEnabled()) {
                logger.debug("Include hand-written descriptions is enabled.");
            }
        }else{
            if (logger.isDebugEnabled()) {
                logger.debug("Include hand-written descriptions is disabled.");
            }
        }
        if(this.separatedDefinitionsEnabled){
            if (logger.isDebugEnabled()) {
                logger.debug("Create separated definition files is enabled.");
            }
            Validate.notEmpty(outputDirectory, "Output directory is required for separated definition files!");
        }else{
            if (logger.isDebugEnabled()) {
                logger.debug("Create separated definition files is disabled.");
            }
        }
        this.definitionOrdering = swagger2MarkupConfig.getDefinitionOrdering();
    }

    @Override
    public MarkupDocument build(){
        definitions(swagger.getDefinitions());
        return this;
    }

    /**
     * Builds the Swagger definitions.
     *
     * @param definitions the Swagger definitions
     */
    private void definitions(Map<String, Model> definitions){
        if(MapUtils.isNotEmpty(definitions)){
            this.markupDocBuilder.sectionTitleLevel1(DEFINITIONS);
            Set<String> definitionNames;
            if (definitionOrdering == null)
              definitionNames = new LinkedHashSet<>();
            else
              definitionNames = new TreeSet<>(definitionOrdering);
            definitionNames.addAll(definitions.keySet());
            for(String definitionName : definitionNames){
                Model model = definitions.get(definitionName);
                if(isNotBlank(definitionName)) {
                    if (checkThatDefinitionIsNotInIgnoreList(definitionName)) {
                        processDefinition(definitions, definitionName, model);
                        if (logger.isInfoEnabled()) {
                            logger.info("Definition processed: {}", definitionName);
                        }
                    }else{
                        if (logger.isDebugEnabled()) {
                            logger.debug("Definition was ignored: {}", definitionName);
                        }
                    }
                }
            }
        }
    }

    private void processDefinition(Map<String, Model> definitions, String definitionName, Model model) {

        if (separatedDefinitionsEnabled) {
            MarkupDocBuilder defDocBuilder = MarkupDocBuilders.documentBuilder(markupLanguage);
            definition(definitions, definitionName, model, defDocBuilder);
            File definitionFile = new File(outputDirectory, resolveDefinitionDocument(definitionName));
            try {
                String definitionDirectory = FilenameUtils.getFullPath(definitionFile.getPath());
                String definitionFileName = FilenameUtils.getName(definitionFile.getPath());

                defDocBuilder.writeToFileWithoutExtension(definitionDirectory, definitionFileName, StandardCharsets.UTF_8);
            } catch (IOException e) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("Failed to write definition file: %s", definitionFile), e);
                }
            }
            if (logger.isInfoEnabled()) {
                logger.info("Separate definition file produced: {}", definitionFile);
            }

            definitionRef(definitionName, this.markupDocBuilder);

        } else {
            definition(definitions, definitionName, model, this.markupDocBuilder);
        }
    }

    /**
     * Checks that the definition is not in the list of ignored definitions.
     *
     * @param definitionName the name of the definition
     * @return true if the definition can be processed
     */
    private boolean checkThatDefinitionIsNotInIgnoreList(String definitionName) {
        return !IGNORED_DEFINITIONS.contains(definitionName);
    }

    /**
     * Builds a concrete definition
     *
     * @param definitionName the name of the definition
     * @param model the Swagger Model of the definition
     * @param docBuilder the docbuilder do use for output
     */
    private void definition(Map<String, Model> definitions, String definitionName, Model model, MarkupDocBuilder docBuilder){
        addDefinitionTitle(definitionName, docBuilder);
        descriptionSection(definitionName, model, docBuilder);
        propertiesSection(definitions, definitionName, model, docBuilder);
        definitionSchema(definitionName, docBuilder);
    }

    private void addDefinitionTitle(String title, MarkupDocBuilder docBuilder) {
        docBuilder.sectionTitleLevel2(title);
    }

    private void definitionRef(String definitionName, MarkupDocBuilder docBuilder){
        addDefinitionTitle(docBuilder.crossReferenceAsString(resolveDefinitionDocument(definitionName), definitionName, definitionName), docBuilder);
    }

    private class DefinitionPropertyDescriptor extends PropertyDescriptor {

        public DefinitionPropertyDescriptor(Type type) {
            super(type);
        }

        @Override
        public String getDescription(Property property, String propertyName) {
            String description;
            if(handWrittenDescriptionsEnabled){
                description = handWrittenPathDescription(type.getName().toLowerCase() + "/" + propertyName.toLowerCase(), DESCRIPTION_FILE_NAME);
                if(isBlank(description)) {
                    if (logger.isInfoEnabled()) {
                        logger.info("Hand-written description file cannot be read. Trying to use description from Swagger source.");
                    }
                    description = defaultString(property.getDescription());
                }
            }
            else{
                description = defaultString(property.getDescription());
            }
            return description;
        }
    }

    private void propertiesSection(Map<String, Model> definitions, String definitionName, Model model, MarkupDocBuilder docBuilder){
        Map<String, Property> properties = getAllProperties(definitions, model);
        Type type = new ObjectType(definitionName, properties);

        String definitionsRelativePath = null;
        if (this.separatedDefinitionsEnabled)
            definitionsRelativePath = "..";
        List<Type> localDefinitions = typeProperties(type, inlineSchemaDepthLevel, new PropertyDescriptor(type), definitionsRelativePath, docBuilder);
        inlineDefinitions(localDefinitions, inlineSchemaDepthLevel - 1, docBuilder);
    }

    private Map<String, Property> getAllProperties(Map<String, Model> definitions, Model model) {
        if(model instanceof RefModel) {
            RefModel refModel = (RefModel)model;
            String ref;
            if(refModel.getRefFormat().equals(RefFormat.INTERNAL)){
                ref = refModel.getSimpleRef();
            }else{
                ref = model.getReference();
            }
            return definitions.containsKey(ref)
                    ? getAllProperties(definitions, definitions.get(ref))
                    : null;
        }
        if(model instanceof ComposedModel) {
            ComposedModel composedModel = (ComposedModel)model;
            ImmutableMap.Builder<String, Property> allProperties = ImmutableMap.builder();
            if(composedModel.getAllOf() != null) {
                for(Model innerModel : composedModel.getAllOf()) {
                    Map<String, Property> innerProperties = getAllProperties(definitions, innerModel);
                    if(innerProperties != null) {
                        allProperties.putAll(innerProperties);
                    }
                }
            }
            return allProperties.build();
        }
        else {
            return model.getProperties();
        }
    }

    private void descriptionSection(String definitionName, Model model, MarkupDocBuilder docBuilder){
        if(handWrittenDescriptionsEnabled){
            String description = handWrittenPathDescription(definitionName.toLowerCase(), DESCRIPTION_FILE_NAME);
            if(isNotBlank(description)){
                docBuilder.paragraph(description);
            }else{
                if (logger.isInfoEnabled()) {
                    logger.info("Hand-written description cannot be read. Trying to use description from Swagger source.");
                }
                modelDescription(model, docBuilder);
            }
        }
        else{
            modelDescription(model, docBuilder);
        }
    }

    private void modelDescription(Model model, MarkupDocBuilder docBuilder) {
        String description = model.getDescription();
        if (isNotBlank(description)) {
            docBuilder.paragraph(description);
        }
    }

    /**
     * Reads a hand-written description
     *
     * @param descriptionFolder the name of the folder where the description file resides
     * @param descriptionFileName the name of the description file
     * @return the content of the file
     */
    private String handWrittenPathDescription(String descriptionFolder, String descriptionFileName){
        for (String fileNameExtension : markupLanguage.getFileNameExtensions()) {
            java.nio.file.Path path = Paths.get(descriptionsFolderPath, descriptionFolder, descriptionFileName + fileNameExtension);
            if (Files.isReadable(path)) {
                if (logger.isInfoEnabled()) {
                    logger.info("Description file processed: {}", path);
                }
                try {
                    return FileUtils.readFileToString(path.toFile(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(String.format("Failed to read description file: %s", path), e);
                    }
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Description file is not readable: {}", path);
                }
            }
        }
        if (logger.isWarnEnabled()) {
            logger.info("No description file found with correct file name extension in folder: {}", Paths.get(descriptionsFolderPath, descriptionFolder));
        }
        return null;
    }

    private void definitionSchema(String definitionName, MarkupDocBuilder docBuilder) {
        if(schemasEnabled) {
            if (isNotBlank(definitionName)) {
                schema(JSON_SCHEMA, schemasFolderPath, definitionName + JSON_SCHEMA_EXTENSION, JSON, docBuilder);
                schema(XML_SCHEMA, schemasFolderPath, definitionName + XML_SCHEMA_EXTENSION, XML, docBuilder);
            }
        }
    }

    private void schema(String title, String schemasFolderPath, String schemaName, String language, MarkupDocBuilder docBuilder) {
        java.nio.file.Path path = Paths.get(schemasFolderPath, schemaName);
        if (Files.isReadable(path)) {
            docBuilder.sectionTitleLevel3(title);
            try {
                docBuilder.source(FileUtils.readFileToString(path.toFile(), StandardCharsets.UTF_8).trim(), language);
            } catch (IOException e) {
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("Failed to read schema file: %s", path), e);
                }
            }
            if (logger.isInfoEnabled()) {
                logger.info("Schema file processed: {}", path);
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Schema file is not readable: {}", path);
            }
        }
    }


    /**
     * Inline definitions should never been referenced in TOC, so they are just text.
     */
    private void addInlineDefinitionTitle(String title, String anchor, MarkupDocBuilder docBuilder) {
        docBuilder.anchor(anchor, null);
        docBuilder.newLine();
        docBuilder.boldTextLine(title);
    }


    private void inlineDefinitions(List<Type> definitions, int depth, MarkupDocBuilder docBuilder) {
        if(CollectionUtils.isNotEmpty(definitions)){
            for (Type definition: definitions) {
                addInlineDefinitionTitle(definition.getName(), definition.getUniqueName(), docBuilder);
                String definitionsRelativePath = null;
                if (this.separatedDefinitionsEnabled)
                    definitionsRelativePath = "..";
                List<Type> localDefinitions = typeProperties(definition, depth, new DefinitionPropertyDescriptor(definition), definitionsRelativePath, docBuilder);
                for (Type localDefinition : localDefinitions)
                    inlineDefinitions(Collections.singletonList(localDefinition), depth - 1, docBuilder);
            }
        }

    }
}
