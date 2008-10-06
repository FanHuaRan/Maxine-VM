/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
/*VCSID=3e3d0ba8-d551-433f-b942-8a07fbad9cbc*/
package com.sun.max.program.option;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Arrays;

import com.sun.max.lang.*;
import com.sun.max.program.*;
import com.sun.max.program.option.gui.*;

/**
 * The {@code OptionSet} class parses and collects options from the command line and
 * configuration files.
 *
 * @author Ben L. Titzer
 */
public class OptionSet {

    /**
     * The {@code Syntax} enum allows different options to be parsed differently,
     * depending on their usage.
     */
    public enum Syntax {
        REQUIRES_EQUALS {
            @Override
            public String getUsage(Option option) {
                return "-" + option.getName() + "=" + option.getType().getValueFormat();
            }
        },
        EQUALS_OR_BLANK {
            @Override
            public String getUsage(Option option) {
                return "-" + option.getName() + "[=" + option.getType().getValueFormat() + "]";
            }
        },
        REQUIRES_BLANK {
            @Override
            public String getUsage(Option option) {
                return "-" + option.getName();
            }
        },
        CONSUMES_NEXT {
            @Override
            public String getUsage(Option option) {
                return "-" + option.getName() + " " + option.getType().getValueFormat();
            }
        };

        public abstract String getUsage(Option option);
    }

    protected final Map<String, Option> _optionMap;
    protected final Map<String, Syntax> _optionSyntax;
    protected final Map<String, String> _optionValues;
    protected final boolean _allowUnrecognizedOptions;

    protected static final String[] NO_ARGUMENTS = {};

    protected String[] _arguments = NO_ARGUMENTS;

    /**
     * Creates an option set that does not allow unrecognized options to be present when
     * {@linkplain #parseArguments(String[]) parsing command line arguments} or
     * {@linkplain #loadOptions(OptionSet) loading options from another option set}.
     */
    public OptionSet() {
        this(false);
    }

    /**
     * Creates an option set.
     *
     * @param allowUnrecognizedOptions
     *            specifies if this option set allows unrecognized options to be present when
     *            {@linkplain #parseArguments(String[]) parsing command line arguments} or
     *            {@linkplain #loadOptions(OptionSet) loading options from another option set}.
     */
    public OptionSet(boolean allowUnrecognizedOptions) {
        _optionValues = new HashMap<String, String>();
        // Using a LinkedHashMap to preserve insertion order when iterating over values
        _optionMap = new LinkedHashMap<String, Option>();
        _optionSyntax = new HashMap<String, Syntax>();
        _allowUnrecognizedOptions = allowUnrecognizedOptions;
    }

    /**
     * Converts this option set into a list of command line arguments, to be used, for example, to pass to an external
     * tool. For each option in this set that has been explicitly set this method will prepend an appropriate option
     * string of appropriate syntactic form (e.g. "-name=value") to the array of arguments passed.
     *
     * @param args
     *            the arguments array to which to prepend the option arguments
     * @return a new array of program arguments that includes these options
     */
    public String[] asArguments() {
        final String[] newArgs = Arrays.copyOf(_arguments, _arguments.length + _optionValues.size());
        int i = 0;
        for (String name : _optionValues.keySet()) {
            final String value = _optionValues.get(name);
            if (_optionSyntax.get(name) == Syntax.REQUIRES_BLANK) {
                newArgs[i++] = "-" + name;
            } else {
                newArgs[i++] = "-" + name + "=" + value;
            }
            // TODO: deal with options that consume next!
        }
        return newArgs;
    }

    /**
     * Gets an option set derived from this option set that contains all the unrecognized options that have been loaded
     * or parsed into this option set. The returned option set also includes a copy of the
     * {@linkplain #getArguments() non-option arguments} from this option set.
     */
    public OptionSet getArgumentsAndUnrecognizedOptions() {
        final OptionSet argumentsAndUnrecognizedOptions = new OptionSet(true);
        for (Map.Entry<String, String> entry : _optionValues.entrySet()) {
            if (!_optionMap.containsKey(entry.getKey())) {
                argumentsAndUnrecognizedOptions._optionValues.put(entry.getKey(), entry.getValue());
            }
        }
        argumentsAndUnrecognizedOptions._arguments = _arguments;
        return argumentsAndUnrecognizedOptions;
    }

    /**
     * Handles an Option.Error raised while loading or parsing values into this option set.
     * <p>
     * This default implementation is to print a usage message and the call {@link System#exit(int)}.
     */
    protected void handleErrorDuringParseOrLoad(Option.Error error, String optionName) {
        System.out.println("Error parsing option -" + optionName + ": " + error.getMessage());
        printHelp(System.out, 78);
        System.exit(1);
    }

    /**
     * Parses a list of command line arguments, processing the leading options (i.e. arguments that start with '-')
     * and returning the "leftover" arguments to the caller. The longest tail of {@code arguments} that starts with a non-option argument can be retrieved after parsing with {@link #getArguments()}.
     *
     * @param arguments
     *            the arguments
     * @return this option set
     */
    public OptionSet parseArguments(String[] arguments) {
        // parse the options
        int i = 0;
        for (; i < arguments.length; i++) {
            final String argument = arguments[i];
            if (argument.charAt(0) == '-') {
                // is the beginning of a valid option.
                final int index = argument.indexOf('=');
                final String optionName = getOptionName(argument, index);
                String value = getOptionValue(argument, index);
                final Syntax syntax = _optionSyntax.get(optionName);
                // check the syntax of this option
                try {
                    checkSyntax(optionName, syntax, value, i, arguments);
                    if (syntax == Syntax.CONSUMES_NEXT) {
                        value = arguments[++i];
                    }
                    setValue(optionName, value);
                } catch (Option.Error error) {
                    handleErrorDuringParseOrLoad(error, optionName);
                }
            } else {
                // is not an option, therefore the start of arguments
                break;
            }
        }

        final int left = arguments.length - i;
        _arguments = new String[left];
        System.arraycopy(arguments, i, _arguments, 0, left);

        if (System.getProperty("useProgramOptionDialog") != null) {
            OptionsDialog.show(null, this);
        }
        return this;
    }

    /**
     * The {@code getArguments()} method gets the leftover command line options
     * from the last call to {@code parseArguments}</code>.
     *
     * @return the leftover command line options
     */
    public String[] getArguments() {
        if (_arguments.length == 0) {
            return _arguments;
        }
        return Arrays.copyOf(_arguments, _arguments.length);
    }

    /**
     * Determines if this option set allows parsing or loading of unrecognized options.
     */
    public boolean allowsUnrecognizedOptions() {
        return _allowUnrecognizedOptions;
    }

    /**
     * The {@code loadSystemProperties()} method loads the value of the valid
     * options from the systems properties with the specified prefix.
     *
     * @param prefix the prefix of each system property, used to disambiguate
     *               these options from other system properties.
     * @return this option set
     */
    public OptionSet loadSystemProperties(String prefix) {
        final Properties systemProperties = System.getProperties();
        final Properties properties = new Properties();
        for (String key : systemProperties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                properties.setProperty(key.substring(prefix.length()), systemProperties.getProperty(key));
            }
        }
        return loadProperties(properties, true);
    }

    /**
     * The {@code storeSystemProperties()} method stores these option values
     * into the system properties.
     *
     * @param prefix the prefix to append to all option names when inserting them
     * into the systems properties
     */
    public void storeSystemProperties(String prefix) {
        // TODO: store all options, or only explicitly set ones?
        for (Map.Entry<String, String> entry : _optionValues.entrySet()) {
            System.setProperty(prefix + entry.getKey(), entry.getValue());
        }
    }

    /**
     * Loads the specified properties into this set of options.
     *
     * @param p
     *            the properties set to load into this set of options
     * @param loadall
     *            true if this method should load all properties in the property set into this option set; false if this
     *            method should only load properties for options already in this option set
     * @return this option set
     */
    public OptionSet loadProperties(Properties p, boolean loadall) {
        if (loadall) {
            // if loadall is specified, load all properties in the set
            for (Object object : p.keySet()) {
                final String name = (String) object;
                final String val = p.getProperty(name);
                try {
                    setValue(name, val);
                } catch (Option.Error error) {
                    handleErrorDuringParseOrLoad(error, name);
                }
            }
        } else {
            // if loadall is not specified, only load options that are in this option set.
            for (Object o : p.keySet()) {
                final String name = (String) o;
                if (_optionMap.containsKey(name)) {
                    final String val = p.getProperty(name);
                    try {
                        setValue(name, val);
                    } catch (Option.Error error) {
                        handleErrorDuringParseOrLoad(error, name);
                    }
                }
            }
        }
        if (System.getProperty("useProgramOptionDialog") != null) {
            OptionsDialog.show(null, this);
        }
        return this;
    }

    /**
     * The {@code loadFile()} method parses properties from a file and loads them into this set of options.
     *
     * @param fname
     *            the filename from while to load the properties
     * @param loadall
     *            true if this method should load all properties in the property set into this option set; false if this
     *            method should only load properties for options already in this option set
     * @return this option set
     * @throws java.io.IOException
     *             if there is a problem opening or reading the file
     */
    public OptionSet loadFile(String fname, boolean loadall) throws IOException, Option.Error {
        final Properties defs = new Properties();
        defs.load(new FileInputStream(new File(fname)));
        return loadProperties(defs, loadall);
    }

    /**
     * Loads a set of options and {@linkplain #getArguments() arguments} from another option set.
     *
     * @param options the option set from which to load the option values
     * @return this option set
     */
    public OptionSet loadOptions(OptionSet options) {
        for (Map.Entry<String, String> entry : options._optionValues.entrySet()) {
            try {
                setValue(entry.getKey(), entry.getValue());
            } catch (Option.Error error) {
                handleErrorDuringParseOrLoad(error, entry.getKey());
            }
        }
        _arguments = options._arguments;

        if (System.getProperty("useProgramOptionDialog") != null) {
            OptionsDialog.show(null, this);
        }
        return this;
    }

    protected void checkSyntax(String optname, Syntax syntax, String value, int cntr, String[] args) {
        if (syntax == Syntax.REQUIRES_BLANK && value != null) {
            throw new Option.Error("syntax error: \"-" + optname + "\" required");
        }
        if (syntax == Syntax.REQUIRES_EQUALS && value == null) {
            throw new Option.Error("syntax error: \"-" + optname + "=value\" required");
        }
        if (syntax == Syntax.CONSUMES_NEXT && value != null) {
            throw new Option.Error("syntax error: \"-" + optname + " value\" required");
        }
    }

    protected String getOptionName(String argument, int equalIndex) {
        if (equalIndex < 0) { // naked option
            return argument.substring(1, argument.length());
        }
        return argument.substring(1, equalIndex);
    }

    protected String getOptionValue(String argument, int equalIndex) {
        if (equalIndex < 0) { // naked option
            return null;
        }
        return argument.substring(equalIndex + 1);
    }

    /**
     * Adds the options of an {@link OptionSet} to this set.
     */
    public void addOptions(OptionSet optionSet) {
        for (Option<?> option : optionSet.getOptions()) {
            final Syntax syntax = optionSet._optionSyntax.get(option.getName());
            addOption(option, syntax);
        }
    }

    /**
     * The {@code addOption()} method adds an option with the {@link Syntax#REQUIRES_EQUALS} syntax to this option set.
     *
     * @param option the new option to add to this set
     * @return the option passed as the argument, after it has been added to this option set
     */
    public <T> Option<T> addOption(Option<T> option) {
        return addOption(option, Syntax.REQUIRES_EQUALS);
    }

    /**
     * The {@code addOption()} method adds an option to this option set.
     *
     * @param option the new option to add to this set
     * @param syntax the syntax of the option, which specifies how to parse the option
     *               from command line parameters
     * @return the option passed as the argument, after it has been added to this option set
     */
    public <T> Option<T> addOption(Option<T> option, Syntax syntax) {
        final String name = option.getName();
        final Option existingOption = _optionMap.put(name, option);
        if (existingOption != null) {
            ProgramError.unexpected("Cannot register more than one option under the same name: " + option.getName());
        }
        _optionSyntax.put(name, syntax);
        return option;
    }

    /**
     * The {@code setSyntax()} method sets the syntax of a particular option.
     *
     * @param option the option for which to change the syntax
     * @param syntax the new syntax for the instruction
     */
    public void setSyntax(Option option, Syntax syntax) {
        _optionSyntax.put(option.getName(), syntax);
    }

    /**
     * The {@code setValue()} method sets the value of the specified option in
     * this option set. If there is no option by the specified name, the name/value
     * pair will simply be remembered.
     *
     * @param name the name of the option
     * @param val  the new value of the option as a string
     * @throws Option.Error if {@code name} denotes an unrecognized option and this
     */
    public void setValue(String name, String value) {
        final String v = value == null ? "" : value;
        final Option opt = _optionMap.get(name);
        if (opt != null) {
            opt.setString(v);
        } else {
            if (!_allowUnrecognizedOptions) {
                throw new Option.Error("unrecognized option -" + name);
            }
        }
        _optionValues.put(name, v);
    }

    public String getStringValue(String name) {
        return _optionValues.get(name);
    }

    /**
     * The {@code hasOptionSpecified()} method checks whether an option with the specified
     * name has been assigned to. An option as been "assigned to" if its value has been set
     * by either parsing arguments (the {@code parseArguments() method} or loading properties
     * from a file or the system properties.
     *
     * @param name the name of the option to query
     * @return true if an option with the specified name has been set; false otherwise
     */
    public boolean hasOptionSpecified(String name) {
        return _optionValues.containsKey(name);
    }

    /**
     * Retrieves the options from this option
     * set, in the order in which they were added.
     * @return an iterable collection of {@code Option} instances, sorted according to insertion order
     */
    public Iterable<Option<?>> getOptions() {
        return StaticLoophole.cast(_optionMap.values());
    }

    /**
     * The {@code getSortedOptions()} method retrieves the options from this option
     * set, sorting all options by their names.
     * @return an iterable collection of {@code Option} instances, sorted according to the name of each option
     */
    public Iterable<Option<?>> getSortedOptions() {
        final List<Option<?>> list = new LinkedList<Option<?>>();
        final TreeSet<String> tree = new TreeSet<String>();
        for (String string : _optionMap.keySet()) {
            tree.add(string);
        }
        for (String string : tree) {
            list.add(_optionMap.get(string));
        }
        return list;
    }

    /**
     * The {@code printHelp()} method prints a textual listing of these options and their syntax
     * to the specified output stream.
     * @param stream the output stream to which to write the help text
     * @param width the length of the line to truncate
     */
    public void printHelp(PrintStream stream, int width) {
        for (Option<?> option : getSortedOptions()) {
            final Option<Object> opt = StaticLoophole.cast(option);
            stream.print("    " + getUsage(opt));
            final Object defaultValue = opt.getDefaultValue();
            if (defaultValue != null) {
                stream.println(" (default: " + opt.getType().unparseValue(defaultValue) + ")");
            } else {
                stream.println();
            }
            stream.print(Strings.formatParagraphs(opt.getHelp(), 8, 0, width));
            stream.println();
            stream.println();
        }
    }

    /**
     * This method gets a usage string for a particular option that describes
     * the range of valid string values that it accepts.
     * @param option the option for which to get usage
     * @return a string describing the usage syntax for the specified option
     */
    public String getUsage(Option option) {
        return _optionSyntax.get(option.getName()).getUsage(option);
    }


    public Option<String> newStringOption(String name, String defaultValue, String help) {
        return addOption(new Option<String>(name, defaultValue, OptionTypes.STRING_TYPE, help));
    }

    public Option<Integer> newIntegerOption(String name, Integer defaultValue, String help) {
        return addOption(new Option<Integer>(name, defaultValue, OptionTypes.INT_TYPE, help));
    }

    public Option<Long> newLongOption(String name, Long defaultValue, String help) {
        return addOption(new Option<Long>(name, defaultValue, OptionTypes.LONG_TYPE, help));
    }

    public Option<Float> newFloatOption(String name, Float defaultValue, String help) {
        return addOption(new Option<Float>(name, defaultValue, OptionTypes.FLOAT_TYPE, help));
    }

    public Option<Double> newDoubleOption(String name, Double defaultValue, String help) {
        return addOption(new Option<Double>(name, defaultValue, OptionTypes.DOUBLE_TYPE, help));
    }

    public Option<List<String>> newStringListOption(String name, String defaultValue, String help) {
        return addOption(new Option<List<String>>(name, defaultValue == null ? null : OptionTypes.COMMA_SEPARATED_STRING_LIST_TYPE.parseValue(defaultValue), OptionTypes.COMMA_SEPARATED_STRING_LIST_TYPE, help));
    }

    public Option<List<String>> newStringListOption(String name, String defaultValue, char separator, String help) {
        final OptionTypes.StringListType type = new OptionTypes.StringListType(separator);
        return addOption(new Option<List<String>>(name, defaultValue == null ? null : type.parseValue(defaultValue), type, help));
    }

    public <Value_Type> Option<List<Value_Type>> newListOption(String name, String defaultValue, Option.Type<Value_Type> elementOptionType, char separator, String help) {
        final OptionTypes.ListType<Value_Type> type = new OptionTypes.ListType<Value_Type>(separator, elementOptionType);
        return addOption(new Option<List<Value_Type>>(name, defaultValue == null ? null : type.parseValue(defaultValue), type, help));
    }

    public Option<File> newFileOption(String name, String defaultValue, String help) {
        return newFileOption(name, OptionTypes.FILE_TYPE.parseValue(defaultValue), help);
    }

    public Option<File> newFileOption(String name, File defaultValue, String help) {
        return addOption(new Option<File>(name, defaultValue, OptionTypes.FILE_TYPE, help));
    }

    public Option<URL> newURLOption(String name, URL defaultValue, String help) {
        return addOption(new Option<URL>(name, defaultValue, OptionTypes.URL_TYPE, help));
    }

    /**
     * @author Thomas Wuerthinger
     * @return An option whose value is an instance of the specified class.
     */
    public <Instance_Type> Option<Instance_Type> newInstanceOption(String name, Class<Instance_Type> klass, Instance_Type defaultValue, String help) {
        return addOption(new Option<Instance_Type>(name, defaultValue, OptionTypes.createInstanceOptionType(klass), help));
    }

    /**
     * @author Thomas Wuerthinger
     * @return An option whose values are instances of the specified classes.
     */
    public <Instance_Type> Option<List<Instance_Type>> newListInstanceOption(String name, String defaultValue, Class<Instance_Type> klass, char separator, String help) {
        final OptionTypes.ListType<Instance_Type> type = OptionTypes.createInstanceListOptionType(klass, separator);
        return addOption(new Option<List<Instance_Type>>(name, (defaultValue == null) ? null : type.parseValue(defaultValue), type, help));
    }

    public Option<Boolean> newBooleanOption(String name, Boolean defaultValue, String help) {
        if (defaultValue != null && defaultValue == false) {
            return addOption(new Option<Boolean>(name, defaultValue, OptionTypes.BOOLEAN_TYPE, help), Syntax.EQUALS_OR_BLANK);
        }
        return addOption(new Option<Boolean>(name, defaultValue, OptionTypes.BOOLEAN_TYPE, help));
    }

    public <Object_Type> Option<Object_Type> newOption(String name, String defaultValue, Option.Type<Object_Type> type, String help) {
        return newOption(name, type.parseValue(defaultValue), type, Syntax.REQUIRES_EQUALS, help);
    }

    public <Object_Type> Option<Object_Type> newOption(String name, Object_Type defaultValue, Option.Type<Object_Type> type, Syntax syntax, String help) {
        return addOption(new Option<Object_Type>(name, defaultValue, type, help));
    }

    public <Enum_Type extends Enum<Enum_Type>> Option<Enum_Type> newEnumOption(String name, Enum_Type defaultValue, Class<Enum_Type> enumClass, String help) {
        return addOption(new Option<Enum_Type>(name, defaultValue, new OptionTypes.EnumType<Enum_Type>(enumClass), help));
    }

    public <Enum_Type extends Enum<Enum_Type>> Option<List<Enum_Type>> newEnumListOption(String name, Iterable<Enum_Type> defaultValue, Class<Enum_Type> enumClass, String help) {
        final List<Enum_Type> list;
        if (defaultValue == null) {
            list = null;
        } else if (defaultValue instanceof List) {
            list = StaticLoophole.cast(defaultValue);
        } else if (defaultValue instanceof Collection) {
            final Collection<Enum_Type> collection = StaticLoophole.cast(defaultValue);
            list = new ArrayList<Enum_Type>(collection);
        } else {
            list = new ArrayList<Enum_Type>();
            for (Enum_Type value : defaultValue) {
                list.add(value);
            }
        }
        final Option<List<Enum_Type>> option = new Option<List<Enum_Type>>(name, list, new OptionTypes.EnumListType<Enum_Type>(enumClass, ','), help);
        return addOption(option);
    }

    public Option<File> newConfigOption(String name, File defaultFile, String help) {
        return addOption(new Option<File>(name, defaultFile, new OptionTypes.ConfigFile(this), help));
    }
}
