package fr.adrienbrault.idea.symfony2plugin.action;


import com.intellij.ide.IdeView;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.StreamUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.Parameter;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import fr.adrienbrault.idea.symfony2plugin.Symfony2Icons;
import fr.adrienbrault.idea.symfony2plugin.action.ui.ServiceArgumentSelectionDialog;
import fr.adrienbrault.idea.symfony2plugin.action.ui.SymfonyCreateService;
import fr.adrienbrault.idea.symfony2plugin.dic.ContainerService;
import fr.adrienbrault.idea.symfony2plugin.stubs.ContainerCollectionResolver;
import fr.adrienbrault.idea.symfony2plugin.util.PhpElementsUtil;
import fr.adrienbrault.idea.symfony2plugin.util.SymfonyBundleUtil;
import fr.adrienbrault.idea.symfony2plugin.util.dict.ServiceUtil;
import fr.adrienbrault.idea.symfony2plugin.util.dict.SymfonyBundle;
import fr.adrienbrault.idea.symfony2plugin.util.yaml.YamlHelper;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.YAMLFileType;
import org.jetbrains.yaml.psi.*;

import java.io.IOException;
import java.util.*;

public class ServiceActionUtil {

    /**
     * Attributes which we should not support in missing arguments constructors for server definition
     */
    public static final String[] INVALID_ARGUMENT_ATTRIBUTES = new String[]{
        "parent", "factory-class", "factory-service", "abstract", "autowire"
    };

    public static void buildFile(AnActionEvent event, final Project project, String templatePath) {
        String extension = templatePath.endsWith(".yml") ? "yml" : "xml" ;

        String fileName = Messages.showInputDialog(project, "File name (without extension)", String.format("Create %s Service", extension), Symfony2Icons.SYMFONY);
        if(fileName == null || StringUtils.isBlank(fileName)) {
            return;
        }

        FileType fileType = templatePath.endsWith(".yml") ? YAMLFileType.YML : XmlFileType.INSTANCE ;

        if(!fileName.endsWith("." + extension)) {
            fileName = fileName.concat("." + extension);
        }

        DataContext dataContext = event.getDataContext();
        IdeView view = LangDataKeys.IDE_VIEW.getData(dataContext);
        if (view == null) {
            return;
        }

        PsiDirectory[] directories = view.getDirectories();
        if(directories.length == 0) {
            return;
        }

        final PsiDirectory initialBaseDir = directories[0];
        if (initialBaseDir == null) {
            return;
        }

        if(initialBaseDir.findFile(fileName) != null) {
            Messages.showInfoMessage("File exists", "Error");
            return;
        }

        String content;
        try {
            content = StreamUtil.readText(ServiceActionUtil.class.getResourceAsStream(templatePath), "UTF-8").replace("\r\n", "\n");
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        final PsiFileFactory factory = PsiFileFactory.getInstance(project);

        String bundleName = "Acme\\DemoBundle";

        SymfonyBundleUtil symfonyBundleUtil = new SymfonyBundleUtil(project);
        SymfonyBundle symfonyBundle = symfonyBundleUtil.getContainingBundle(initialBaseDir);

        if(symfonyBundle != null) {
            bundleName = StringUtils.strip(symfonyBundle.getNamespaceName(), "\\");
        }

        String underscoreBundle = bundleName.replace("\\", ".").toLowerCase();
        if(underscoreBundle.endsWith("bundle")) {
            underscoreBundle = underscoreBundle.substring(0, underscoreBundle.length() - 6);
        }

        content = content.replace("{{ BundleName }}", bundleName).replace("{{ BundleNameUnderscore }}", underscoreBundle);

        final PsiFile file = factory.createFileFromText(fileName, fileType, content);

        ApplicationManager.getApplication().runWriteAction(() -> {
            CodeStyleManager.getInstance(project).reformat(file);
            initialBaseDir.add(file);
        });

        PsiFile psiFile = initialBaseDir.findFile(fileName);
        if(psiFile != null) {
            view.selectElement(psiFile);
        }

    }

    @NotNull
    public static Set<String> getPossibleServices(@NotNull Project project, @NotNull String type, @NotNull Map<String, ContainerService> serviceClasses) {
        PhpClass typeClass = PhpElementsUtil.getClassInterface(project, type);
        if(typeClass == null) {
            return Collections.emptySet();
        }

        return getPossibleServices(typeClass, serviceClasses);
    }

    @NotNull
    public static Set<String> getPossibleServices(@NotNull PhpClass phpClass, @NotNull Map<String, ContainerService> serviceClasses) {
        List<ContainerService> matchedContainer = new ArrayList<>(ServiceUtil.getServiceSuggestionForPhpClass(phpClass, serviceClasses));
        if(matchedContainer.size() == 0) {
            return Collections.emptySet();
        }

        // weak service have lower priority
        Collections.sort(matchedContainer, new SymfonyCreateService.ContainerServicePriorityWeakComparator());

        // lower priority of services like "doctrine.orm.default_entity_manager"
        Collections.sort(matchedContainer, new SymfonyCreateService.ContainerServicePriorityNameComparator());

        Set<String> possibleServices = new LinkedHashSet<>();
        for(ContainerService containerService: matchedContainer) {
            possibleServices.add(containerService.getName());
        }

        return possibleServices;
    }

    @NotNull
    public static Collection<XmlTag> getXmlContainerServiceDefinition(PsiFile psiFile) {

        Collection<XmlTag> xmlTags = new ArrayList<>();

        for(XmlTag xmlTag: PsiTreeUtil.getChildrenOfTypeAsList(psiFile.getFirstChild(), XmlTag.class)) {
            if(xmlTag.getName().equals("container")) {
                for(XmlTag servicesTag: xmlTag.getSubTags()) {
                    if(servicesTag.getName().equals("services")) {
                        for(XmlTag parameterTag: servicesTag.getSubTags()) {
                            if(parameterTag.getName().equals("service")) {
                                xmlTags.add(parameterTag);
                            }
                        }
                    }
                }
            }
        }

        return xmlTags;
    }

    public static class ServiceYamlContainer {

        @NotNull
        private final YAMLKeyValue serviceKey;
        @Nullable
        private final YAMLKeyValue argument;

        @NotNull
        private final String className;

        public ServiceYamlContainer(@NotNull YAMLKeyValue serviceKey, @Nullable YAMLKeyValue argument, @NotNull String className) {
            this.serviceKey = serviceKey;
            this.argument = argument;
            this.className = className;
        };

        @Nullable
        public YAMLKeyValue getArgument() {
            return argument;
        }

        @NotNull
        public String getClassName() {
            return className;
        }

        @NotNull
        public YAMLKeyValue getServiceKey() {
            return serviceKey;
        }

        /**
         * fo<caret>o:
         *   class: foo
         *   arguments: []
         */
        @Nullable
        public static ServiceYamlContainer create(@NotNull YAMLKeyValue yamlServiceKeyValue) {
            YAMLMapping childOfType = PsiTreeUtil.getChildOfType(yamlServiceKeyValue, YAMLMapping.class);
            if(childOfType == null) {
                return null;
            }

            YAMLKeyValue aClass = childOfType.getKeyValueByKey("class");
            if(aClass == null) {
                return null;
            }

            YAMLValue value = aClass.getValue();
            if(!(value instanceof YAMLScalar)) {
                return null;
            }

            String serviceClass = ((YAMLScalar) value).getTextValue();
            if (StringUtils.isBlank(serviceClass)) {
                return null;
            }

            return new ServiceYamlContainer(yamlServiceKeyValue, childOfType.getKeyValueByKey("arguments"), serviceClass);
        }

    }

    /**
     * Gets all services inside yaml file with "arguments" key context
     */
    @NotNull
    public static Collection<ServiceYamlContainer> getYamlContainerServiceArguments(@NotNull YAMLFile yamlFile) {

        Collection<ServiceYamlContainer> services = new ArrayList<>();

        for(YAMLKeyValue yamlKeyValue : YamlHelper.getQualifiedKeyValuesInFile(yamlFile, "services")) {
            ServiceYamlContainer serviceYamlContainer = ServiceYamlContainer.create(yamlKeyValue);
            if(serviceYamlContainer != null) {
                services.add(serviceYamlContainer);
            }
        }

        return services;
    }

    @Nullable
    public static List<String> getXmlMissingArgumentTypes(@NotNull XmlTag xmlTag, boolean collectOptionalParameter, @NotNull ContainerCollectionResolver.LazyServiceCollector collector) {

        PhpClass resolvedClassDefinition = getPhpClassFromXmlTag(xmlTag, collector);
        if (resolvedClassDefinition == null) {
            return null;
        }

        Method constructor = resolvedClassDefinition.getConstructor();
        if(constructor == null) {
            return null;
        }

        int serviceArguments = 0;

        for (XmlTag tag : xmlTag.getSubTags()) {
            if("argument".equals(tag.getName())) {
                serviceArguments++;
            }
        }

        Parameter[] parameters = collectOptionalParameter ? constructor.getParameters() : PhpElementsUtil.getFunctionRequiredParameter(constructor);
        if(parameters.length <= serviceArguments) {
            return null;
        }

        final List<String> args = new ArrayList<>();

        for (int i = serviceArguments; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String s = parameter.getDeclaredType().toString();
            args.add(s);
        }

        return args;
    }

    @Nullable
    public static PhpClass getPhpClassFromXmlTag(@NotNull XmlTag xmlTag, @NotNull ContainerCollectionResolver.LazyServiceCollector collector) {

        XmlAttribute classAttribute = xmlTag.getAttribute("class");
        if(classAttribute == null) {
            return null;
        }

        String value = classAttribute.getValue();
        if(StringUtils.isBlank(value)) {
            return null;
        }

        // @TODO: cache defs
        PhpClass resolvedClassDefinition = ServiceUtil.getResolvedClassDefinition(xmlTag.getProject(), value, collector);
        if(resolvedClassDefinition == null) {
            return null;
        }

        return resolvedClassDefinition;
    }

    @Nullable
    public static List<String> getYamlMissingArgumentTypes(Project project, ServiceActionUtil.ServiceYamlContainer container, boolean collectOptionalParameter, @NotNull ContainerCollectionResolver.LazyServiceCollector collector) {

        PhpClass resolvedClassDefinition = ServiceUtil.getResolvedClassDefinition(project, container.getClassName(), collector);
        if(resolvedClassDefinition == null) {
            return null;
        }

        Method constructor = resolvedClassDefinition.getConstructor();
        if(constructor == null) {
            return null;
        }


        int serviceArguments = -1;
        if(container.getArgument() != null) {

            PsiElement yamlCompoundValue = container.getArgument().getValue();
            if(yamlCompoundValue instanceof YAMLCompoundValue) {
                List<PsiElement> yamlArrayOnSequenceOrArrayElements = YamlHelper.getYamlArrayOnSequenceOrArrayElements((YAMLCompoundValue) yamlCompoundValue);
                if(yamlArrayOnSequenceOrArrayElements != null) {
                    serviceArguments = yamlArrayOnSequenceOrArrayElements.size();
                }
            }

        } else {
            serviceArguments = 0;
        }

        if(serviceArguments == -1) {
            return null;
        }

        Parameter[] parameters = collectOptionalParameter ? constructor.getParameters() : PhpElementsUtil.getFunctionRequiredParameter(constructor);
        if(parameters.length <= serviceArguments) {
            return null;
        }

        final List<String> args = new ArrayList<>();

        for (int i = serviceArguments; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            String s = parameter.getDeclaredType().toString();
            args.add(s);
        }

        return args;
    }

    public static boolean isValidXmlParameterInspectionService(@NotNull XmlTag xmlTag) {

        // we dont support some attributes right now
        for(String s : INVALID_ARGUMENT_ATTRIBUTES) {
            if(xmlTag.getAttribute(s) != null) {
                return false;
            }
        }

        // <service><factory/></service>
        // symfony2 >= 2.6
        for (XmlTag tag : xmlTag.getSubTags()) {
            if("factory".equals(tag.getName())) {
                return false;
            }
        }

        return true;
    }

    public static void fixServiceArgument(@NotNull List<String> args, final @NotNull XmlTag xmlTag) {
        fixServiceArgument(xmlTag.getProject(), args, new XmlInsertServicesCallback(xmlTag));
    }

    public static void fixServiceArgument(@NotNull Project project, @NotNull List<String> args, final @NotNull InsertServicesCallback callback) {

        Map<String, ContainerService> services = ContainerCollectionResolver.getServices(project);

        Map<String, Set<String>> resolved = new LinkedHashMap<>();
        for (String arg : args) {
            resolved.put(arg, ServiceActionUtil.getPossibleServices(project, arg, services));
        }

        // we got an unique service list, not need to provide ui
        if(isUniqueServiceMap(resolved)) {
            List<String> items = new ArrayList<>();
            for (Map.Entry<String, Set<String>> stringSetEntry : resolved.entrySet()) {
                Set<String> value = stringSetEntry.getValue();
                if(value.size() > 0 ) {
                    items.add(value.iterator().next());
                } else {
                    items.add("?");
                }
            }

            callback.insert(items);

            return;
        }

        ServiceArgumentSelectionDialog.createDialog(project, resolved, callback::insert);
    }

    public static boolean isUniqueServiceMap(Map<String, Set<String>> resolvedServices) {

        for (Map.Entry<String, Set<String>> stringSetEntry : resolvedServices.entrySet()) {
            if(stringSetEntry.getValue().size() > 1) {
                return false;
            }
        }

        return true;

    }

    public static void addServices(List<String> items, XmlTag xmlTag) {
        for (String item : items) {

            if(StringUtils.isBlank(item)) {
                item = "?";
            }

            XmlTag tag = XmlElementFactory.getInstance(xmlTag.getProject()).createTagFromText(String.format("<argument type=\"service\" id=\"%s\"/>", item), xmlTag.getLanguage());
            xmlTag.addSubTag(tag, false);
        }
    }

    public static interface InsertServicesCallback {
        public void insert(List<String> items);
    }

    public static class XmlInsertServicesCallback implements InsertServicesCallback {

        @NotNull
        private final XmlTag xmlTag;

        public XmlInsertServicesCallback(final @NotNull XmlTag xmlTag) {
            this.xmlTag = xmlTag;
        }

        @Override
        public void insert(List<String> items) {
            addServices(items, this.xmlTag);
        }

    }

}
