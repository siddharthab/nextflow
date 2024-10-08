(syntax-page)=

# Syntax

This page provides a comprehensive description of the Nextflow language.

## Comments

Nextflow uses Java-style comments: `//` for a line comment, and `/* ... */` for a block comment:

```groovy
println 'Hello world!' // line comment

/*
 * block comment
 */
println 'Hello again!'
```

## Top-level declarations

A Nextflow script may contain the following top-level declarations:

- Shebang
- Feature flags
- Includes
- Parameter definitions
- Workflow definitions
- Process definitions
- Function definitions
- Enum types
- Output block

These declarations are in turn composed of statements and expressions.

Alternatively, a script may contain one or more [statements](#statements), as long as there are no top-level declarations. In this case, the entire script will be treated as an entry workflow.

For example, the following script:

```groovy
println 'Hello world!'
```

Is equivalent to:

```groovy
workflow {
    println 'Hello world!'
}
```

:::{warning}
Top-level declarations and statements can not be mixed at the same level. If your script has top-level declarations, all statements must be contained within top-level declarations such as the entry workflow.
:::

### Shebang

The first line of a script can be a [shebang](https://en.wikipedia.org/wiki/Shebang_(Unix)):

```sh
#!/usr/bin/env nextflow
```

### Feature flag

A feature flag declaration is an assignment, where the target should be a valid {ref}`feature flag <config-feature-flags>` and the source should be a literal (i.e. number, string, boolean):

```groovy
nextflow.preview.topic = true
```

### Include

An include declaration consists of an *include source* and one or more *include clauses*:

```groovy
include { foo ; bar as baz } from './some/module'
```

The include source should be a string literal and should refer to either a local path (e.g. `./module.nf`) or a plugin (e.g. `plugin/nf-hello`).

Each include clause should specify a name, and may also specify an *alias*. In the example above, `bar` is included under the alias `baz`.

Include clauses can be separated by newlines or semi-colons, or they can be specified as separate includes:

```groovy
// newlines
include {
    foo
    bar as baz
} from './some/module'

// separate includes
include { foo } from './some/module'
include { bar as baz } from './some/module'
```

The following definitions can be included:

- Functions
- Processes
- Named workflows

### Parameter

A parameter declaration is an assignment, where the target should be a pipeline parameter and the source should be an expression:

```groovy
params.message = 'Hello world!'
```

Parameters supplied via command line options, params files, and config files take precedence over parameter definitions in a script.

(syntax-workflow)=

### Workflow

A workflow consists of a name and a body. The workflow body consists of a *main* section, with additional sections for *takes*, *emits*, and *publishers* (shown later):

```groovy
workflow greet {
    take:
    greetings

    main:
    messages = greetings.map { v -> "$v world!" }

    emit:
    messages
}
```

- The take, emit, and publish sections are optional. If they are not specified, the `main:` section label can be omitted.

- The take section consists of one or more parameters.

- The main section consists of one or more [statements](#statements).

- The emit section consists of one or more *emit statements*. An emit statement can be a [variable name](#variable), an [assignment](#assignment), or an [expression statement](#expression-statement). If an emit statement is an expression statement, it must be the only emit.

An alternative workflow form, known as an *entry workflow*, has no name and may only define a main and publish section:

```groovy
workflow {
    main:
    greetings = Channel.of('Bonjour', 'Ciao', 'Hello', 'Hola')
    messages = greetings.map { v -> "$v world!" }
    greetings.view { it -> '$it world!' }

    publish:
    messages >> 'messages'
}
```

- Only one entry workflow may be defined in a script.

- The `main:` section label can be omitted if the publish section is not specified.

- The publish section consists of one or more *publish statements*. A publish statement is a [right-shift expression](#binary-expressions), where the left-hand side is an expression that refers to a value in the workflow body, and the right-hand side is an expression that returns a string.

- The publish section can also be specified in named workflows as a convenience, but is intended mainly to be used in the entry workflow.

In order for a script to be executable, it must either define an entry workflow or use the implicit workflow syntax described [above](#top-level-declarations).

Entry workflow definitions are ignored when a script is included as a module. This way, the same script can be included as a module or executed as a pipeline.

(syntax-process)=

### Process

A process consists of a name and a body. The process body consists of one or more [statements](#statements). A minimal process definition must return a string:

```groovy
process sayHello {
    """
    echo 'Hello world!'
    """
}
```

A process may define additional sections for *directives*, *inputs*, *outputs*, *script*, *shell*, *exec*, and *stub*:

```groovy
process greet {
    // directives
    errorStrategy 'retry'
    tag { "${greeting}/${name}" }

    input: 
    val greeting
    val name

    output:
    stdout

    script: // or shell: or exec:
    """
    echo '${greeting}, ${name}!'
    """

    stub:
    """
    # do nothing
    """
}
```

- Each of these additional sections are optional. Directives do not have an explicit section label, but are simply defined first.

- The `script:` section label can be omitted only when there are no other sections in the body.

- Sections must be defined in the order shown above, with the exception of the output section, which can alternatively be specified after the script and stub.

Each section may contain one or more statements. For directives, inputs, and outputs, these statements must be [function calls](#function-call). Refer to {ref}`process-reference` for the set of available input qualifiers, output qualifiers, and directives.

The script section can be substituted with a shell or exec section:

```groovy
process greetShell {
    input: 
    val greeting

    shell:
    '''
    echo '!{greeting}, ${USER}!'
    '''
}

process greetExec {
    input: 
    val greeting
    val name

    exec:
    message = "${greeting}, ${name}!"

    output:
    val message
}
```

The script, shell, and stub sections must return a string in the same manner as a [function](#function).

Refer to {ref}`process-page` for more information on the semantics of each process section.

(syntax-function)=

### Function

A function consists of a name, parameter list, and a body:

```groovy
def greet(greeting, name) {
    println "${greeting}, ${name}!"
}
```

The function body consists of one or more [statements](#statements). The last statement is implicitly treated as a return statement if it is an [expression statement](#expression-statement) that returns a value.

The [return statement](#return) can be used to explicitly return from a function:

```groovy
// return with no value
def greet(greeting, name) {
    if( !greeting || !name )
        return
    println "${greeting}, ${name}!"
}

// return a value
def fib(x) {
    if( x <= 1 )
        return x
    fib(x - 1) + fib(x - 2)
}
```

### Enum type

An enum type declaration consists of a name and a body, which consists of a comma-separated list of identifiers:

```groovy
enum Day {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY
}
```

Enum values can be accessed as `Day.MONDAY`, `Day.TUESDAY`, and so on.

:::{note}
Enum types cannot be included across modules at this time.
:::

### Output block

The output block consists of one or more *target blocks*. A target block consists of a *target name* and one or more *target directives* for configuring the corresponding publish target:

```groovy
output {
    'fastq' {
        path 'samples'
        index {
            path 'index.csv'
        }
    }
}
```

Only one output block may be defined in a script. Refer to {ref}`workflow-output-def` for the set of available target directives.

## Statements

Statements should be separated by a newline or semi-colon:

```groovy
// newline
println 'Hello!'
println 'Hello again!'

// semi-colon
println 'Hello!' ; println 'Hello again!'
```

### Variable declaration

Variables can be declared with the `def` keyword:

```groovy
def x = 42
```

Multiple variables can be declared in a single statement as long as the initializer is a [list literal](#list) with as many elements as declared variables:

```groovy
def (x, y) = [ 1, 2 ]
```

Every variable has a *scope*, which determines the region of code in which the variable is defined.

Variables declared in a function, as well as the parameters of that function, exist for the duration of that function call. The same applies to closures.

Workflow inputs exist for the entire workflow body. Variables declared in the main section exist for the main, emit, and publish sections. Named outputs are not considered variable declarations and therefore do not have any scope.

Process input variables exist for the entire process body. Variables declared in the process script, shell, exec, and stub sections exist only in their respective section, with one exception -- in these sections, a variable can be declared with the `def` keyword, in which case it will also exist in the output section.

Variables declared in an if or else branch exist only within that branch:

```groovy
if( true )
    def x = 'foo'
println x           // error: `x` is undefined

// solution: declare `x` outside of if branch
def x
if( true )
    x = 'foo'
println x
```

A variable cannot be declared with the same name as another variable in the same scope or any enclosing scope:

```groovy
def clash(x) {
    def x           // error: `x` is already declared
    if( true )
        def x       // error: `x` is already declared
}
```

### Assignment

An assignment statement consists of a *target* expression and a *source* expression separated by an equals sign:

```groovy
v = 42
list[0] = 'first'
map.key = 'value'
```

The target expression must be a [variable](#variable), [index](#binary-expressions), or [property](#binary-expressions) expression. The source expression can be any expression.

Multiple variables can be assigned in a single statement as long as the source expression is a [list literal](#list) with as many elements as assigned variables:

```groovy
(x, y) = [ 1, 2 ]
```

### Expression statement

Any [expression](#expressions) can also be a statement.

In general, the only expressions that can have any effect as expression statements are function calls that have side effects (e.g. `println`) or an implicit return statement (e.g. in a function or closure).

### assert

An assert statement consists of the `assert` keyword followed by a boolean expression, with an optional error message separated by a colon:

```groovy
assert 2 + 2 == 4 : 'The math broke!'
```

If the condition is false, an error will be raised with the given error message.

### if / else

An if/else statement consists of an *if branch* and an optional *else branch*. Each branch consists of a boolean expression in parentheses, followed by either a single statement or a *block statement* (one or more statements in curly braces).

```groovy
def x = Math.random()
if( x < 0.5 ) {
    println 'You lost.'
}
else {
    println 'You won!'
}
```

If the condition is true, the if branch will be executed, otherwise the else branch will be executed.

If / else statements can be chained any number of times by making the else branch another if / else statement:

```groovy
def grade = 89
if( grade >= 90 )
    println 'You get an A!'
else if( grade >= 80 )
    println 'You get a B!'
else if( grade >= 70 )
    println 'You get a C!'
else if( grade >= 60 )
    println 'You get a D!'
else
    println 'You failed.'
```

A more verbose way to write the same code would be:

```groovy
def grade = 89
if( grade >= 90 ) {
    println 'You get an A!'
}
else {
    if( grade >= 80 ) {
        println 'You get a B!'
    }
    else {
        if( grade >= 70 ) {
            println 'You get a C!'
        }
        else {
            if( grade >= 60 ) {
                println 'You get a D!'
            }
            else {
                println 'You failed.'
            }
        }
    }
}
```

### return

A return statement consists of the `return` keyword with an optional expression:

```groovy
def add(a, b) {
    return a + b
}

def sayHello(name) {
    if( !name )
        return
    println "Hello, ${name}!"
}
```

Return statements can only be used in functions and closures. In the case of a nested closure, the return statement will return from the nearest enclosing closure.

If a function or closure has multiple return statements (including implicit returns), all of the return statements should either return a value or return nothing. If a function or closure does return a value, it should do so for every conditional branch.

```groovy
def isEven1(n) {
    if( n % 2 == 1 )
        return          // error: return value is required here
    return true
}

def isEven2(n) {
    if( n % 2 == 0 )
        return true
                        // error: return value is required here
}
```

Note that if the last statement is not a return or expression statement (implicit return), it is equivalent to appending an empty return.

### throw

A throw statement consists of the `throw` keyword followed by an expression that returns an error type:

```groovy
throw new Exception('something failed!')
```

:::{note}
In general, the appropriate way to raise an error is to use the {ref}`error <stdlib-functions>` function:
```groovy
error 'something failed!'
```
:::

### try / catch

A try / catch statement consists of a *try block* followed by any number of *catch clauses*:

```groovy
def text = null
try {
    text = file('foo.txt').text
}
catch( IOException e ) {
    log.warn "Could not load foo.txt"
}
```

The try block will be executed, and if an error is raised and matches the expected error type of a catch clause, the code in that catch clause will be executed. If no catch clause is matched, the error will be raised to the next enclosing try / catch statement, or to the Nextflow runtime.

## Expressions

An expression represents a value. A *literal* value is an expression whose value is known at compile-time, such as a number, string, or boolean. All other expressions must be evaluated at run-time.

Every expression has a *type*, which may be resolved at compile-time or run-time.

### Variable

A variable expression is a reference to a variable or other named value:

```groovy
def x = 42

x
// -> 42
```

### Number

A number literal can be an integer or floating-point number, and can be positive or negative. Integers can specified in binary with `0b`, octal with `0`, or hexadecimal with `0x`. Floating-point numbers can use scientific notation with the `e` or `E` prefix. Underscores can be used as thousands separators to make long numbers more readable.

```groovy
// integer
42
-1
0b1001  // -> 9
031     // -> 25
0xabcd  // -> 43981

// real
3.14
-0.1
1.59e7  // -> 15_900_000
1.59e-7 // -> 0.000000159
```

### Boolean

A boolean literal can be `true` or `false`:

```groovy
assert true != false
assert !true == false
assert true == !false
```

### Null

The null literal is specified as `null`. It can be used to represent an "empty" value:

```groovy
def x = null
x = 42
```

:::{note}
Using a null value in certain expressions (e.g. the object of a property expression or method call) will lead to a "null reference" error. It is best to avoid the use of `null` where possible.
:::

### String

A string literal consists of arbitrary text enclosed by single or double quotes:

```groovy
println "I said 'hello'"
println 'I said "hello" again!'
```

A triple-quoted string can span multiple lines:

```groovy
println '''
    Hello,
    How are you today?
    '''

println """
    We don't have to escape quotes anymore!
    Even "double" quotes!
    """
```

A *slashy string* is enclosed by slashes instead of quotes:

```groovy
/no escape!/
```

Slashy strings can also span multiple lines:

```groovy
/
Patterns in the code,
Symbols dance to match and find,
Logic unconfined.
/
```

Note that a slashy string cannot be empty because it would become a line comment.

### Dynamic string

Double-quoted strings can be interpolated using the `${}` placeholder, which can contain any expression:

```groovy
def names = ['Thing 1', 'Thing 2']
println "Hello, ${names.join(' and ')}!"
// -> Hello, Thing 1 and Thing 2!
```

If the expression is a name or simple property expression (one or more identifiers separated by dots), the curly braces can be omitted:

```groovy
def name = [first: '<FIRST_NAME>', last: '<LAST_NAME>']
println "Hello, ${name.first} ${name.last}!"
// -> Hello, <FIRST_NAME> <LAST_NAME>!
```

Multi-line double-quoted strings can also be interpolated:

```groovy
"""
blastp \
    -in $input \
    -out $output \
    -db $blast_db \
    -html
"""
```

Single-quoted strings are not interpolated:

```groovy
println 'Hello, ${names.join(" and ")}!'
// -> Hello, ${names.join(" and ")}!
```

### List

A list literal consists of a comma-separated list of zero or more expressions, enclosed in square brackets:

```groovy
[1, 2, 3]
```

### Map

A map literal consists of a comma-separated list of one or more *map entries*, where each map entry consists of a *key expression* and *value expression* separated by a colon, enclosed in square brackets:

```groovy
[foo: 1, bar: 2, baz: 3]
```

An empty map is specified with a single colon to distinguish it from an empty list:

```groovy
[:]
```

Both the key and value can be any expression. Identifier keys are treated as string literals (i.e. the quotes can be omitted). A variable can be used as a key by enclosing it in parentheses:

```groovy
def x = 'foo'
[(x): 1]
// -> ['foo': 1]
```

### Closure

A closure, also known as an anonymous function, consists of a parameter list followed by zero or more statements, enclosed in curly braces:

```groovy
{ a, b -> a + b }
```

The above closure takes two arguments and returns their sum.

The closure body is identical to that of a [function](#function). Statements should be separated by newlines or semi-colons, and the last statement is implicitly treated as a [return statement](#return):

```groovy
{ v ->
    println 'Hello!'
    println "We're in a closure!"
    println 'Goodbye...'
    v * v
}
```

Closures can access variables outside of their scope:

```groovy
def factor = 2
println [1, 2, 3].collect { v -> factor * v }
// -> [2, 4, 6]
```

And they can declare local variables that exist only for the lifetime of each closure invocation:

```groovy
def result = 0
[1, 2, 3].each { v ->
    def squared = v * v
    result += squared
}

println result
// -> 14
```

Refer to the {ref}`standard library <stdlib-page>` and {ref}`operator <operator-page>` reference pages for examples of closures being used in practice.

### Index expression

An index expression consists of a *left expression* and a *right expression*, with the right expression enclosed in square brackets:

```groovy
myList[0]
```

### Property expression

A property expression consists of an *object expression* and a *property*, separated by a dot:

```groovy
file.text
```

The property must be an identifier or string literal.

### Function call

A function call consists of a name and argument list:

```groovy
printf('Hello %s!\n', 'World')
```

A *method call* consists of an *object expression* and a function call separated by a dot:

```groovy
myList.size()
```

The argument list may contain any number of *positional arguments* and *named arguments*:

```groovy
file('hello.txt', checkIfExists: true)
```

The named arguments are collected into a map and provided as the first positional argument to the function. Thus the above function call can be rewritten as:

```groovy
file([checkIfExists: true], 'hello.txt')
```

The argument name must be an identifier or string literal.

When the function call is also an [expression statement](#expression-statement) and there is at least one argument, the parentheses can be omitted:

```groovy
// positional args
printf 'Hello %s!\n', 'World'

// positional and named args
file 'hello.txt', checkIfExists: true
```

If the last argument is a closure, it can be specified outside of the parentheses:

```groovy
// closure arg with additional args
[1, 2, 3].inject('result:') { acc, v -> acc + ' ' + v }

// single closure arg
[1, 2, 3].each() { v -> println v }

// single closure arg without parentheses
[1, 2, 3].each { v -> println v }
```

### Constructor call

A constructor call consists of the `new` keyword followed by a *type name* and an argument list enclosed in parentheses:

```groovy
new java.util.Date()
```

If the type is implicitly available in the script, the *fully-qualified type name* can be elided to the *simple type name*:

```groovy
new Date()
```

Refer to {ref}`stdlib-default-imports` for the set of types which are implicitly available in Nextflow scripts.

### Unary expressions

A unary expression consists of a *unary operator* followed by an expression:

```groovy
!(2 + 2 == 4)
```

The following unary operators are available:

- `~`: bitwise NOT
- `!`: logical NOT
- `+`: unary plus
- `-`: unary minus

### Binary expressions

A binary expression consists of a *left expression* and a *right expression* separated by a *binary operator*:

```groovy
2 + 2
```

The following binary operators are available:

- `**`: power (i.e. exponentiation)
- `*`: multiplication
- `/`: division
- `%`: remainder (i.e. modulo)
- `+`: addition
- `-`: subtraction
- `<<`: left shift
- `>>`: right shift
- `>>>`: unsigned right shift
- `..`: inclusive range
- `..<`: right-exclusive range
- `as`: type cast
- `instanceof`: type relation
- `!instanceof`: negated type relation
- `<`: less than
- `>`: greater than
- `<=`: less than or equals
- `>=`: greater than or equals
- `in`: membership
- `!in`: negated membership
- `==`: equals
- `!=`: negated equals
- `<=>`: spaceship (i.e. three-way comparison)
- `=~`: regex find
- `==~`: regex match
- `&`: bitwise AND
- `^`: bitwise XOR (exclusive or)
- `|`: bitwise OR
- `&&`: logical AND
- `||`: logical OR
- `?:` elvis (i.e. short ternary)

### Ternary expression

A ternary expression consists of a *test expression*, a *true expression*, and a *false expression*, separated by a question mark and a colon:

```groovy
println x % 2 == 0 ? 'x is even!' : 'x is odd!'
```

If the test expression is true, the true expression is evaluated, otherwise the false expression is evaluated.

### Parentheses

Any expression can be enclosed in parentheses:

```groovy
1 + 2 * 3
// -> 1 + 6 -> 7

(1 + 2) * 3
// -> 3 * 3 -> 9
```

### Precedence

Compound expressions are evaluated in the following order:

- parentheses
- property expressions
- function calls
- index expressions
- `~`,  `!`
- `**`
- `+`, `-` (unary)
- `*`, `/`, `%`
- `+`, `-` (binary)
- `<<`, `>>>`, `>>`, `..`, `..<`
- `as`
- `instanceof`, `!instanceof`
- `<`, `>`, `<=`, `>=`, `in`, `!in`
- `==`, `!=`, `<=>`
- `=~`, `==~`
- `&`
- `^`
- `|`
- `&&`
- `||`
- `?:` (ternary)
- `?:` (elvis)

## Deprecations

The following legacy features were excluded from this page because they are deprecated:

- The `addParams` and `params` clauses of include declarations (see {ref}`module-params`)
- The `when:` section of a process definition (see {ref}`process-when`)
- The implicit `it` closure parameter