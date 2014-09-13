
package comp;

import ast.*;
import lexer.*;
import java.io.*;
import java.util.*;

public class Compiler {

	// compile must receive an input with an character less than
	// p_input.lenght
	public Program compile(char[] input, PrintWriter outError) {

		error = new CompilerError(new PrintWriter(outError));
		symbolTable = new SymbolTable();
		lexer = new Lexer(input, error);
		error.setLexer(lexer);

		Program program = null;
		try {
			lexer.nextToken();
			if ( lexer.token == Symbol.EOF )
				error.show("Unexpected end of file");
			program = program();
			if ( lexer.token != Symbol.EOF ) {
				program = null;
				error.show("End of file expected");
			}
		}
		catch (Exception e) {
			// the below statement prints the stack of called methods.
			// of course, it should be removed if the compiler were
			// a production compiler.

			// e.printStackTrace();
			program = null;
		}

		return program;
	}

	private Program program() {
		// Program ::= KraClass { KraClass }

		classDec();
		while (lexer.token == Symbol.CLASS)
			classDec();
		return new Program(new ArrayList<KraClass>());
	}

	private void classDec() {
		// Note que os m�todos desta classe n�o correspondem exatamente �s
		// regras
		// da gram�tica. Este m�todo classDec, por exemplo, implementa
		// a produ��o KraClass (veja abaixo) e partes de outras produ��es.

		/*
		 * KraClass ::= ``class'' Id [ ``extends'' Id ] "{" MemberList "}"
		 * MemberList ::= { Qualifier Member } 
		 * Member ::= InstVarDec | MethodDec
		 * InstVarDec ::= Type IdList ";" 
		 * MethodDec ::= Qualifier Type Id "("[ FormalParamDec ] ")" "{" StatementList "}" 
		 * Qualifier ::= [ "static" ]  ( "private" | "public" )
		 */
		if ( lexer.token != Symbol.CLASS ) error.show("'class' expected");
		lexer.nextToken();
		if ( lexer.token != Symbol.IDENT )
			error.show(CompilerError.ident_expected);
		String className = lexer.getStringValue();
		symbolTable.putInGlobal(className, new KraClass(className));
		// Pegando referencia de classe do hashmap pra poder modificar como necessario
		KraClass ref = symbolTable.getInGlobal(className);
		lexer.nextToken();
		if ( lexer.token == Symbol.EXTENDS ) {
			lexer.nextToken();
			if ( lexer.token != Symbol.IDENT )
				error.show(CompilerError.ident_expected);
			String superclassName = lexer.getStringValue();
			//procurando a super classe caso a mesma exista 
			KraClass superclass = symbolTable.getInGlobal(superclassName);
			//se a classe nao eh encontrada na symbol table entao sinalizar erro
			if(superclass == null)
				error.show("Cannot extend from Super class "+superclassName+", the class doesn't exist");
			//se tudo der certo, entao setar superclasse da KraClass =)
			ref.setSuperClass(superclass);
			lexer.nextToken();
		}
		if ( lexer.token != Symbol.LEFTCURBRACKET )
			error.show("{ expected", true);
		lexer.nextToken();
		
		while (lexer.token == Symbol.PRIVATE || lexer.token == Symbol.PUBLIC  || lexer.token == Symbol.STATIC) {
			
			Symbol qualifier;
			//adicionando flag para verificar se houve declaracao disso como static
			boolean staticFlag = false;
			//se o token for static, vai pro proximo, e defina flag como true, senao volta pra falso
			if(lexer.token == Symbol.STATIC){
				lexer.nextToken();
				staticFlag = true;
			}else{
				staticFlag = false;
			}
			switch (lexer.token) {
			case PRIVATE:
				lexer.nextToken();
				qualifier = Symbol.PRIVATE;
				break;
			case PUBLIC:
				lexer.nextToken();
				qualifier = Symbol.PUBLIC;
				break;
			default:
				error.show("private, or public expected");
				qualifier = Symbol.PUBLIC;
			}
			Type t = type();
			if ( lexer.token != Symbol.IDENT )
				error.show("Identifier expected");
			String name = lexer.getStringValue();
			lexer.nextToken();
			if ( lexer.token == Symbol.LEFTPAR )
				//passando staticFlag para fazer verificacoes na semantica do metodo
				methodDec(t, name, qualifier);
			else if ( qualifier != Symbol.PRIVATE )
				error.show("Attempt to declare a public instance variable");
			else{
				//Adicionando criacao de instanceVarDec como objeto e adicionando a classe em questao
				InstanceVariableList vList = instanceVarDec(t, name, className, staticFlag);
				//Manda static flag pra ver se entra na lista de static ou nao
				ref.addElements(vList, staticFlag);
			}
		}
		if ( lexer.token != Symbol.RIGHTCURBRACKET )
			error.show("public/private or \"}\" expected");
		lexer.nextToken();

	}

	//Modifiquei instanceVarDec para aceitar o nome da classe e a flag dizendo se eh static ou nao
	private InstanceVariableList instanceVarDec(Type type, String name, String className, boolean staticFlag) {
		// InstVarDec ::= [ "static" ] "private" Type IdList ";"
		//cria nova lista de instancias
		InstanceVariableList varList = new InstanceVariableList();
		//retorna o objeto da classe em que as instancias se encontrarao
		KraClass classObj = symbolTable.getInGlobal(className);
		//se a classe nao for encontrada, mostrar erro
		if(classObj == null)
			error.show("Could not find reference to class "+className);
		//Procura instancia sendo ela static ou nao dentro da lista de instancias da classe, se nao achar entao adicione
		// a instancia a lista
		if((classObj.searchInstance(name, staticFlag)) == null)
			varList.addElement(new InstanceVariable(name, type));
		else{
			//senao mostre erro apropriado se ja existe
			if(staticFlag)
				error.show("Duplicate declaration for static instance "+name);
			else
				error.show("Duplicate declaration for instance "+name);
		}
		while (lexer.token == Symbol.COMMA) {
			lexer.nextToken();
			if ( lexer.token != Symbol.IDENT )
				error.show("Identifier expected");
			String variableName = lexer.getStringValue();
			//Procura as seguintes declaracoes nas listas correspondentes novamente e adiciona, assim como antes
			if((classObj.searchInstance(variableName, staticFlag)) == null)
				varList.addElement(new InstanceVariable(variableName, type));
			else{
				if(staticFlag)
					error.show("Duplicate declaration for static instance "+name);
				else
					error.show("Duplicate declaration for instance "+name);
			}
			lexer.nextToken();
		}
		if ( lexer.token != Symbol.SEMICOLON )
			error.show(CompilerError.semicolon_expected);
		lexer.nextToken();
		return varList;
	}

	private void methodDec(Type type, String name, Symbol qualifier) {
		/*
		 * MethodDec ::= Qualifier Return Id "("[ FormalParamDec ] ")" "{"
		 *                StatementList "}"
		 */

		lexer.nextToken();
		if ( lexer.token != Symbol.RIGHTPAR ) formalParamDec();
		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");

		lexer.nextToken();
		if ( lexer.token != Symbol.LEFTCURBRACKET ) error.show("{ expected");

		lexer.nextToken();
		statementList();
		if ( lexer.token != Symbol.RIGHTCURBRACKET ) error.show("} expected");
		//ao fim da analise de metodo, pra nao me esquecer, limpe a localTable
		symbolTable.removeLocalIdent();
		lexer.nextToken();

	}

	//Adicionando retorno de LocalVarList
	private LocalVarList localDec() {
		// LocalDec ::= Type IdList ";"
		//Crio uma localDecList
		LocalVarList localDecList = new LocalVarList();
		Type type = type();
		if ( lexer.token != Symbol.IDENT ) error.show("Identifier expected");
		Variable v = new Variable(lexer.getStringValue(), type);
		//Vou adicionando os elementos na lista 
		localDecList.addElement(v);
		lexer.nextToken();
		while (lexer.token == Symbol.COMMA) {
			lexer.nextToken();
			if ( lexer.token != Symbol.IDENT )
				error.show("Identifier expected");
			//Conforme o loop vai rodando tambem vou adicionando 
			v = new Variable(lexer.getStringValue(), type);
			localDecList.addElement(v);
			lexer.nextToken();
		}
		return localDecList;
	}

	private ParamList formalParamDec() {
		// FormalParamDec ::= ParamDec { "," ParamDec }
		//Cria uma parameter list
		ParamList paramList; 
		//Inicializo
		paramList = new ParamList();
		//Adiciono o parametro que recebo de paramDec na lista
		paramList.addElement(paramDec());
		//E continuo adicionando enquanto houverem mais
		while (lexer.token == Symbol.COMMA) {
			lexer.nextToken();
			paramList.addElement(paramDec());
		}
		//Apago a tabela local, ja que ja verifiquei se existiam parametros repetidos
		symbolTable.removeLocalIdent();
		return paramList;
	}

	//Retorna Parameter
	private Parameter paramDec() {
		// ParamDec ::= Type Id
		Type t;
		String name;
		Variable p; 
		Parameter newP = null;
		
		
		t = type();
		
		
		if ( lexer.token != Symbol.IDENT ) error.show("Identifier expected");
		
		name = lexer.getStringValue();
		//verifico se ja nao existe parametro com mesmo nome na lista de parametros atraves da localTable
		if((p = symbolTable.getInLocal(lexer.getStringValue())) == null){
			//Crio um novo Parameter object
			newP = new Parameter(name, t);
			symbolTable.putInLocal(lexer.getStringValue(), newP);
		}else{
			error.show("Duplicate parameters for method");
		}
		
		lexer.nextToken();
		return newP;
	}

	private Type type() {
		// Type ::= BasicType | Id
		Type result;

		switch (lexer.token) {
		case VOID:
			result = Type.voidType;
			break;
		case INT:
			result = Type.intType;
			break;
		case BOOLEAN:
			result = Type.booleanType;
			break;
		case STRING:
			result = Type.stringType;
			break;
		case IDENT:
			// # corrija: fa�a uma busca na TS para buscar a classe
			// IDENT deve ser uma classe.
			
			//uso o metodo getInGlobal para procurar a definicao da classe na tabela global 
			result = symbolTable.getInGlobal(lexer.getStringValue());
			if(result == null) error.show(lexer.getStringValue()+" is not a class");
			break;
		default:
			error.show("Type expected");
			result = Type.undefinedType;
		}
		lexer.nextToken();
		return result;
	}

	private void compositeStatement() {

		lexer.nextToken();
		statementList();
		if ( lexer.token != Symbol.RIGHTCURBRACKET )
			error.show("} expected");
		else
			lexer.nextToken();
	}

	private void statementList() {
		// CompStatement ::= "{" { Statement } "}"
		Symbol tk;
		// statements always begin with an identifier, if, read, write, ...
		while ((tk = lexer.token) != Symbol.RIGHTCURBRACKET
				&& tk != Symbol.ELSE)
			statement();
	}

	private void statement() {
		/*
		 * Statement ::= Assignment ``;'' | IfStat |WhileStat | MessageSend
		 *                ``;'' | ReturnStat ``;'' | ReadStat ``;'' | WriteStat ``;'' |
		 *               ``break'' ``;'' | ``;'' | CompStatement | LocalDec
		 */

		switch (lexer.token) {
		case THIS:
		case IDENT:
		case SUPER:
		case INT:
		case BOOLEAN:
		case STRING:
			assignExprLocalDec();
			break;
		case RETURN:
			returnStatement();
			break;
		case READ:
			readStatement();
			break;
		case WRITE:
			writeStatement();
			break;
		case WRITELN:
			writelnStatement();
			break;
		case IF:
			ifStatement();
			break;
		case BREAK:
			breakStatement();
			break;
		case WHILE:
			whileStatement();
			break;
		case SEMICOLON:
			nullStatement();
			break;
		case LEFTCURBRACKET:
			compositeStatement();
			break;
		default:
			error.show("Statement expected");
		}
	}

	/*
	 * retorne true se 'name' � uma classe declarada anteriormente. � necess�rio
	 * fazer uma busca na tabela de s�mbolos para isto.
	 */
	private boolean isType(String name) {
		return this.symbolTable.getInGlobal(name) != null;
	}

	/*
	 * AssignExprLocalDec ::= Expression [ ``$=$'' Expression ] | LocalDec
	 */
	private Expr assignExprLocalDec() {

		if ( lexer.token == Symbol.INT || lexer.token == Symbol.BOOLEAN
				|| lexer.token == Symbol.STRING ||
				// token � uma classe declarada textualmente antes desta
				// instru��o
				(lexer.token == Symbol.IDENT && isType(lexer.getStringValue())) ) {
			/*
			 * uma declara��o de vari�vel. 'lexer.token' � o tipo da vari�vel
			 * 
			 * AssignExprLocalDec ::= Expression [ ``$=$'' Expression ] | LocalDec 
			 * LocalDec ::= Type IdList ``;''
			 */
			localDec();
		}
		else {
			/*
			 * AssignExprLocalDec ::= Expression [ ``$=$'' Expression ]
			 */
			expr();
			if ( lexer.token == Symbol.ASSIGN ) {
				lexer.nextToken();
				expr();
			}
		}
		return null;
	}

	private ExprList realParameters() {
		ExprList anExprList = null;

		if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
		lexer.nextToken();
		if ( startExpr(lexer.token) ) anExprList = exprList();
		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
		lexer.nextToken();
		return anExprList;
	}

	private void whileStatement() {

		lexer.nextToken();
		if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
		lexer.nextToken();
		expr();
		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
		lexer.nextToken();
		statement();
	}

	private void ifStatement() {

		lexer.nextToken();
		if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
		lexer.nextToken();
		expr();
		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
		lexer.nextToken();
		statement();
		if ( lexer.token == Symbol.ELSE ) {
			lexer.nextToken();
			statement();
		}
	}

	private void returnStatement() {

		lexer.nextToken();
		expr();
		if ( lexer.token != Symbol.SEMICOLON )
			error.show(CompilerError.semicolon_expected);
		lexer.nextToken();
	}

	private void readStatement() {
		lexer.nextToken();
		if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
		lexer.nextToken();
		while (true) {
			if ( lexer.token == Symbol.THIS ) {
				lexer.nextToken();
				if ( lexer.token != Symbol.DOT ) error.show(". expected");
				lexer.nextToken();
			}
			if ( lexer.token != Symbol.IDENT )
				error.show(CompilerError.ident_expected);

			String name = lexer.getStringValue();
			lexer.nextToken();
			if ( lexer.token == Symbol.COMMA )
				lexer.nextToken();
			else
				break;
		}

		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
		lexer.nextToken();
		if ( lexer.token != Symbol.SEMICOLON )
			error.show(CompilerError.semicolon_expected);
		lexer.nextToken();
	}

	private void writeStatement() {

		lexer.nextToken();
		if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
		lexer.nextToken();
		exprList();
		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
		lexer.nextToken();
		if ( lexer.token != Symbol.SEMICOLON )
			error.show(CompilerError.semicolon_expected);
		lexer.nextToken();
	}

	private void writelnStatement() {

		lexer.nextToken();
		if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
		lexer.nextToken();
		exprList();
		if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
		lexer.nextToken();
		if ( lexer.token != Symbol.SEMICOLON )
			error.show(CompilerError.semicolon_expected);
		lexer.nextToken();
	}

	private void breakStatement() {
		lexer.nextToken();
		if ( lexer.token != Symbol.SEMICOLON )
			error.show(CompilerError.semicolon_expected);
		lexer.nextToken();
	}

	private void nullStatement() {
		lexer.nextToken();
	}

	private ExprList exprList() {
		// ExpressionList ::= Expression { "," Expression }

		ExprList anExprList = new ExprList();
		anExprList.addElement(expr());
		while (lexer.token == Symbol.COMMA) {
			lexer.nextToken();
			anExprList.addElement(expr());
		}
		return anExprList;
	}

	private Expr expr() {

		Expr left = simpleExpr();
		Symbol op = lexer.token;
		if ( op == Symbol.EQ || op == Symbol.NEQ || op == Symbol.LE
				|| op == Symbol.LT || op == Symbol.GE || op == Symbol.GT ) {
			lexer.nextToken();
			Expr right = simpleExpr();
			left = new CompositeExpr(left, op, right);
		}
		return left;
	}

	private Expr simpleExpr() {
		Symbol op;

		Expr left = term();
		while ((op = lexer.token) == Symbol.MINUS || op == Symbol.PLUS
				|| op == Symbol.OR) {
			lexer.nextToken();
			Expr right = term();
			left = new CompositeExpr(left, op, right);
		}
		return left;
	}

	private Expr term() {
		Symbol op;

		Expr left = signalFactor();
		while ((op = lexer.token) == Symbol.DIV || op == Symbol.MULT
				|| op == Symbol.AND) {
			lexer.nextToken();
			Expr right = signalFactor();
			left = new CompositeExpr(left, op, right);
		}
		return left;
	}

	private Expr signalFactor() {
		Symbol op;
		if ( (op = lexer.token) == Symbol.PLUS || op == Symbol.MINUS ) {
			lexer.nextToken();
			return new SignalExpr(op, factor());
		}
		else
			return factor();
	}

	/*
	 * Factor ::= BasicValue | "(" Expression ")" | "!" Factor | "null" |
	 *      ObjectCreation | PrimaryExpr
	 * 
	 * BasicValue ::= IntValue | BooleanValue | StringValue 
	 * BooleanValue ::=  "true" | "false" 
	 * ObjectCreation ::= "new" Id "(" ")" 
	 * PrimaryExpr ::= "super" "." Id "(" [ ExpressionList ] ")"  | 
	 *                 Id  |
	 *                 Id "." Id | 
	 *                 Id "." Id "(" [ ExpressionList ] ")" |
	 *                 Id "." Id "." Id "(" [ ExpressionList ] ")" |
	 *                 "this" | 
	 *                 "this" "." Id | 
	 *                 "this" "." Id "(" [ ExpressionList ] ")"  | 
	 *                 "this" "." Id "." Id "(" [ ExpressionList ] ")"
	 */
	private Expr factor() {

		Expr e;
		ExprList exprList;
		String messageName, ident;

		switch (lexer.token) {
		// IntValue
		case NUMBER:
			return literalInt();
			// BooleanValue
		case TRUE:
			lexer.nextToken();
			return LiteralBoolean.True;
			// BooleanValue
		case FALSE:
			lexer.nextToken();
			return LiteralBoolean.False;
			// StringValue
		case LITERALSTRING:
			String literalString = lexer.getLiteralStringValue();
			lexer.nextToken();
			return new LiteralString(literalString);
			// "(" Expression ")" |
		case LEFTPAR:
			lexer.nextToken();
			e = expr();
			if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
			lexer.nextToken();
			return new ParenthesisExpr(e);

			// "!" Factor
		case NOT:
			lexer.nextToken();
			e = expr();
			return new UnaryExpr(e, Symbol.NOT);
			// "null"
		case NULL:
			lexer.nextToken();
			return new NullExpr();
			// ObjectCreation ::= "new" Id "(" ")"
		case NEW:
			lexer.nextToken();
			if ( lexer.token != Symbol.IDENT )
				error.show("Identifier expected");

			String className = lexer.getStringValue();
			/*
			 * // encontre a classe className in symbol table KraClass 
			 *      aClass = symbolTable.getInGlobal(className); 
			 *      if ( aClass == null ) ...
			 */

			lexer.nextToken();
			if ( lexer.token != Symbol.LEFTPAR ) error.show("( expected");
			lexer.nextToken();
			if ( lexer.token != Symbol.RIGHTPAR ) error.show(") expected");
			lexer.nextToken();
			/*
			 * return an object representing the creation of an object
			 */
			return null;
			/*
          	 * PrimaryExpr ::= "super" "." Id "(" [ ExpressionList ] ")"  | 
          	 *                 Id  |
          	 *                 Id "." Id | 
          	 *                 Id "." Id "(" [ ExpressionList ] ")" |
          	 *                 Id "." Id "." Id "(" [ ExpressionList ] ")" |
          	 *                 "this" | 
          	 *                 "this" "." Id | 
          	 *                 "this" "." Id "(" [ ExpressionList ] ")"  | 
          	 *                 "this" "." Id "." Id "(" [ ExpressionList ] ")"
			 */
		case SUPER:
			// "super" "." Id "(" [ ExpressionList ] ")"
			lexer.nextToken();
			if ( lexer.token != Symbol.DOT ) {
				error.show("'.' expected");
			}
			else
				lexer.nextToken();
			if ( lexer.token != Symbol.IDENT )
				error.show("Identifier expected");
			messageName = lexer.getStringValue();
			/*
			 * para fazer as confer�ncias sem�nticas, procure por 'messageName'
			 * na superclasse/superclasse da superclasse etc
			 */
			lexer.nextToken();
			exprList = realParameters();
			break;
		case IDENT:
			/*
          	 * PrimaryExpr ::=  
          	 *                 Id  |
          	 *                 Id "." Id | 
          	 *                 Id "." Id "(" [ ExpressionList ] ")" |
          	 *                 Id "." Id "." Id "(" [ ExpressionList ] ")" |
			 */

			String firstId = lexer.getStringValue();
			lexer.nextToken();
			if ( lexer.token != Symbol.DOT ) {
				// Id
				// retorne um objeto da ASA que representa um identificador
				return null;
			}
			else { // Id "."
				lexer.nextToken(); // coma o "."
				if ( lexer.token != Symbol.IDENT ) {
					error.show("Identifier expected");
				}
				else {
					// Id "." Id
					lexer.nextToken();
					ident = lexer.getStringValue();
					if ( lexer.token == Symbol.DOT ) {
						// Id "." Id "." Id "(" [ ExpressionList ] ")"
						/*
						 * se o compilador permite vari�veis est�ticas, � poss�vel
						 * ter esta op��o, como
						 *     Clock.currentDay.setDay(12);
						 * Contudo, se vari�veis est�ticas n�o estiver nas especifica��es,
						 * sinalize um erro neste ponto.
						 */
						lexer.nextToken();
						if ( lexer.token != Symbol.IDENT )
							error.show("Identifier expected");
						messageName = lexer.getStringValue();
						lexer.nextToken();
						exprList = this.realParameters();

					}
					else if ( lexer.token == Symbol.LEFTPAR ) {
						// Id "." Id "(" [ ExpressionList ] ")"
						exprList = this.realParameters();
						/*
						 * para fazer as confer�ncias sem�nticas, procure por
						 * m�todo 'ident' na classe de 'firstId'
						 */
					}
					else {
						// retorne o objeto da ASA que representa Id "." Id
					}
				}
			}
			break;
		case THIS:
			/*
			 * Este 'case THIS:' trata os seguintes casos: 
          	 * PrimaryExpr ::= 
          	 *                 "this" | 
          	 *                 "this" "." Id | 
          	 *                 "this" "." Id "(" [ ExpressionList ] ")"  | 
          	 *                 "this" "." Id "." Id "(" [ ExpressionList ] ")"
			 */
			lexer.nextToken();
			if ( lexer.token != Symbol.DOT ) {
				// only 'this'
				// retorne um objeto da ASA que representa 'this'
				// confira se n�o estamos em um m�todo est�tico
				return null;
			}
			else {
				lexer.nextToken();
				if ( lexer.token != Symbol.IDENT )
					error.show("Identifier expected");
				ident = lexer.getStringValue();
				lexer.nextToken();
				// j� analisou "this" "." Id
				if ( lexer.token == Symbol.LEFTPAR ) {
					// "this" "." Id "(" [ ExpressionList ] ")"
					/*
					 * Confira se a classe corrente possui um m�todo cujo nome �
					 * 'ident' e que pode tomar os par�metros de ExpressionList
					 */
					exprList = this.realParameters();
				}
				else if ( lexer.token == Symbol.DOT ) {
					// "this" "." Id "." Id "(" [ ExpressionList ] ")"
					lexer.nextToken();
					if ( lexer.token != Symbol.IDENT )
						error.show("Identifier expected");
					lexer.nextToken();
					exprList = this.realParameters();
				}
				else {
					// retorne o objeto da ASA que representa "this" "." Id
					/*
					 * confira se a classe corrente realmente possui uma
					 * vari�vel de inst�ncia 'ident'
					 */
					return null;
				}
			}
			break;
		default:
			error.show("Expression expected");
		}
		return null;
	}

	private LiteralInt literalInt() {

		LiteralInt e = null;

		// the number value is stored in lexer.getToken().value as an object of
		// Integer.
		// Method intValue returns that value as an value of type int.
		int value = lexer.getNumberValue();
		lexer.nextToken();
		return new LiteralInt(value);
	}

	private static boolean startExpr(Symbol token) {

		return token == Symbol.FALSE || token == Symbol.TRUE
				|| token == Symbol.NOT || token == Symbol.THIS
				|| token == Symbol.NUMBER || token == Symbol.SUPER
				|| token == Symbol.LEFTPAR || token == Symbol.NULL
				|| token == Symbol.IDENT || token == Symbol.LITERALSTRING;

	}

	private SymbolTable		symbolTable;
	private Lexer			lexer;
	private CompilerError	error;

}
