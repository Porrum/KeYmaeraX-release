angular.module('formula', ['ngSanitize']);

/** Renders a formula into hierarchically structured spans */
angular.module('formula')
  .directive('k4Formula', ['$compile', '$http', '$sce', function($compile, $http, $sce) {
    return {
        restrict: 'AE',
        scope: {
            userId: '=',
            proofId: '=',
            nodeId: '=',
            formula: '=',
            highlight: '=',
            collapsed: '=?',
            onTactic: '&',     // onTactic(formulaId, tacticId)
            onTwoPositionTactic: '&',
            onInputTactic: '&' // onInputTactic(formulaId, tacticId, input)
        },
        link: function(scope, element, attrs) {
            var span = function(id, content, depth) {
                if (scope.highlight && (content.type === 'formula' || content.type === 'derivative' || content.type === 'symbol')) {
                    return '<span class="hl" id="' + id + '"' +
                             'onmouseover="$(event.target).addClass(\'hlhover\');"' +
                             'onmouseout="$(event.target).removeClass(\'hlhover\');"' +
                             'k4-droppable on-drop="dndSink(\'' + id + '\').formulaDrop(dragData)"' +
                             'on-drag-enter="dndSink(\'' + id + '\').formulaDragEnter(dragData)"' +
                             'on-drag-leave="dndSink(\'' + id + '\').formulaDragLeave(dragData)"' +
                             'ng-click="formulaClick(\'' + id + '\', $event)"' +
                             'ng-right-click="formulaRightClick(\'' + id + '\', $event)"' +
                             // drag-and-drop tooltip
                             'uib-tooltip-template="\'templates/formulaDndTooltipTemplate.html\'"' +
                             'tooltip-popup-delay="500" tooltip-placement="bottom"' +
                             'tooltip-trigger="none" tooltip-is-open="dndTooltip.isOpen(\'' + id + '\')"' +
                             // axiom/tactic application popover
                             'uib-popover-template="\'templates/axiomPopoverTemplate.html\'"' +
                             'popover-is-open="tacticPopover.isOpen(\'' + id + '\')"' +
                             'popover-trigger="none"' +
                             'popover-placement="auto bottom">' + content.text + '</span>';
                } else {
                    return content.text;
                }
            }

            var precedence =
              [
              //Programs.
              "Choice" ,
              "Sequence" ,
              "Loop" ,
              "Assign" ,
              "NDetAssign" ,
              "Test" ,
              "NFODEProduct" ,
              "ODEProduct" ,
              "AtomicODE" ,
              //Formulas
              "equiv" ,
              "imply" ,
              "or" ,
              "and" ,
              "not" ,
              "boxmodality"  ,
              "diamondmodality" ,
              "modality" ,
              "forall" ,
              "exists" ,
              "equals" ,
              "notEqual" ,
              "lt"  ,
              "leq" ,
              "geq" ,
              "gt" ,
              "formuladerivative" ,
              "predicateconstant" ,
              //Terms.
              "add" ,
              "subtract" ,
              "neg" ,
              "multiply" ,
              "divide" ,
              "exp" ,
              "function" ,
              "programconstant" , //real-valued.
              "number" ,
              // Atoms
              "ProgramConstant" ,
              "ContEvolveProgramConstant",
              "applypredicate" ,
              "true" ,
              "false" ,
              "apply",
              "derivative" ,
              "differentialsymbol"
              // names of variables and numbers would be listed last -> not being contained means the same (index = -1)
              ].reverse()

            var parens = {
              "program": ["\ &#123;","\ &#125;"],
              "term": ["(",")"],
              "formula": ["(",")"]
            }

            var needsParens = function(parent, child) {
                var childPrecedence = precedence.indexOf(child.name);
                var parentPrecedence = precedence.indexOf(parent.name);
                return childPrecedence > parentPrecedence;
            }

            var parensIfNeeded = function(parent, child, childType, depth, collapsed) {
                if(needsParens(parent, child)) {
                  return parens[childType][0] + parseFormulaHelper(child, depth, collapsed) + parens[childType][1]
                } else {
                  return parseFormulaHelper(child, depth, collapsed)
                }
              }

            // Recursively generate sequent HTML
            var parseFormulaHelper = function(json, depth, collapsed) {
                var items = [];
                if (json.hasOwnProperty("children") && $.isArray(json.children)) {
                    var c = json.children;
                    var content;
                    switch (json.name) {
                        case "not":
                            var left = parensIfNeeded(json, c[0], 'formula', depth + 1, collapsed);
                            content = {text: "&not;" + left, type: 'formula'};
                            break;

                        case "and":
                            var left = parensIfNeeded(json, c[0], 'formula', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'formula', depth + 1, collapsed);
                            content = {text: left + " &#8743; " + right, type: 'formula'};
                            break;

                        case "or":
                            var left = parensIfNeeded(json, c[0], 'formula', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'formula', depth + 1, collapsed);
                            content = {text: left + " &#8744; " + right, type: 'formula'};
                            break;

                        case "imply":
                            var left = parensIfNeeded(json, c[0], 'formula', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'formula', depth + 1, collapsed);
                            content = {text: collapsed ? left + " → " + right : left + "<br/>→<br/>" + right, type: 'formula'};
                            break;

                        case "equiv":
                            var left = parensIfNeeded(json, c[0], 'formula', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'formula', depth + 1, collapsed);
                            content = {text: left + " &#8596; " + right, type: 'formula'};
                            break;

                        case "lt":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "&lt;" + right, type: 'formula'};
                            break;

                        case "leq":
                            var left = parseFormulaHelper(c[0], depth + 1, collapsed);
                            var right = parseFormulaHelper(c[1], depth + 1, collapsed);
                            content = {text: left + "&leq;" + right, type: 'formula'};
                            break;

                        case "equals":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "=" + right, type: 'formula'};
                            break;

                        case "notEquals":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "&ne;" + right, type: 'formula'};
                            break;

                        case "geq":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "&geq;" + right, type: 'formula'};
                            break;

                        case "gt":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "&gt;" + right, type: 'formula'};
                            break;

                        case "neg":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            content = {text: "-" + left, type: 'term'};
                            break;

                        case "add":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "+" + right, type: 'term'};
                            break;

                        case "subtract":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "-" + right, type: 'term'};
                            break;

                        case "multiply":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "&middot;" + right, type: 'term'};
                            break;

                        case "divide":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "/" + right, type: 'term'};
                            break;

                        case "exp":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + "<sup>" + right + "</sup>", type: 'term'};
                            break;

                        case "forall":
                            var vars = json.variables[0];
                            for (var i = 1; i < json.variables.length; i++) {
                                vars = vars + "," + json.variables[i];
                            }
                            content = {text: "&forall;" + vars + " (" + parseFormulaHelper(c[0], depth + 1, collapsed) + ")", type: 'formula'}
                            break;

                        case "exists":
                            var vars = json.variables[0];
                            for (var i = 1; i < json.variables.length; i++) {
                                vars = vars + "," + json.variables[i];
                            }
                            content = {text: "&exist;" + vars + " (" + parseFormulaHelper(c[0], depth + 1, collapsed) + ")", type: 'formula'}
                            break;

                        case "boxmodality":
                            var left = parseFormulaHelper(c[0], depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'formula', depth + 1, collapsed);
                            content = {text: "\ &#91;" + left + "\ &#93; " + right, type: 'formula'};
                            break;

                        case "diamondmodality":
                            var left = parseFormulaHelper(c[0], depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'formula', depth + 1, collapsed);
                            content = {text: "<" + left + "> " + right, type: 'formula'};
                            break;

                        case "Assign":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: left + " := " + right, type: 'program'};
                            break;

                        case "NDetAssign":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            content = {text: left + ":= *", type: 'program'};
                            break;

                        case "Test":
                            var left = parensIfNeeded(json, c[0], 'formula', depth + 1, collapsed);
                            content = {text: " ?" + left, type: 'program'};
                            break;

                         case "IfThen":
                            var left = parseFormulaHelper(c[0], depth+1, collapsed)
                            var right = parseFormulaHelper(c[1], depth+1, collapsed)
                            content = {text: "if (" + left + ") then \ &#123;" + right + "\ &#125; fi", type: 'program'}
                            break;

                        case "IfThenElse":
                            var condT = parseFormulaHelper(c[0], depth+1, collapsed)
                            var thenT = parseFormulaHelper(c[1], depth+1, collapsed)
                            var elseT = parseFormulaHelper(c[2], depth+1, collapsed)
                            content = {text: "if " + condT + " then \ &#123;" + thenT + "\ &#125; else \ &#123;" + elseT + "\ &#125; fi", type: 'program'}
                            break;

                        case "Loop":
                            var left = parseFormulaHelper(c[0], depth + 1, collapsed);
                            content = {text: "\ &#123;" + left + "\ &#125;<sup>*</sup>", type: 'program'};
                            break;

                        case "Sequence":
                            var left = parensIfNeeded(json, c[0], 'program', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'program', depth + 1, collapsed);
                            content = {text: collapsed ? left + "; " + right : left + ";<br/>" + right, type: 'program'};
                            break;

                        case "Choice":
                            var left = parensIfNeeded(json, c[0], 'program', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'program', depth + 1, collapsed);
                            content = {text: collapsed ? left + " &#8746; " + right : left + "<br/>&#8746;<br/>" + right, type: 'program'};
                            break;

                        case "AtomicODE":
                            var x = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var theta = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            content = {text: x + "=" + theta, type: 'program'};
                            break;

                        case "ODEProduct":
                            var left = parseFormulaHelper(c[0], depth + 1, collapsed);
                            var right = parseFormulaHelper(c[1], depth + 1, collapsed);
                            if (c[1].name != "EmptyODE") {
                              content = {text: left + ", " + right, type: 'program'};
                            } else {
                              content = {text: left, type: 'program'};
                            }
                            break;

                        case "EmptyODE":
                            content = {text: "", type: 'program'}
                            break;

                        case "NFODEProduct":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            var right = parensIfNeeded(json, c[1], 'term', depth + 1, collapsed);
                            if (c[1].name != "EmptyODE") {
                              if(c[1].name == "AtomicODE" || c[1].name == "ODEProduct") {
                                content = {text: "\ &#123;" + left + ", " + right + "\ &#125;", type: 'program'};
                              } else content = {text: "\ &#123;" + left + " &amp; " + right + "\ &#125;", type: 'program'};
                            } else {
                              content = {text: left, type: 'program'};
                            }
                            break;

                        case "formuladerivative":
                            content = {text: "(" + parseFormulaHelper(c[0], depth, collapsed) + ")'", type: 'derivative'}
                            break;

                        case "derivative":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            content = {text: left + "'", type: 'derivative'};
                            break;

                        case "differentialsymbol":
                            var left = parensIfNeeded(json, c[0], 'term', depth + 1, collapsed);
                            content = {text: left + "'", type: 'derivative'};
                            break;

                        case "Anything": content = {text: "?", type: 'symbol'}; break;
                        case "Nothing": content = {text: "", type: 'symbol'}; break;

                        case "apply":
                            var name = json.fnName;
                            var sort = json.sort == 'Real' ? 'term' : (json.sort == 'Bool' ? 'formula' : 'unknown');
                            if (c.length > 0 && c[0].name != "Nothing") {
                              var args = $.map(c, function(arg, i) {
                                return parseFormulaHelper(arg, depth, collapsed);
                              }).join(',');
                              content = {text: name + "(" + args + ")", type: sort};
                            } else {
                              content = {text: name + "()", type: sort};
                            }
                            break;

                        default:
                            var seqs = [];
                            for (var i = 0; i < c.length; i++) {
                                seqs.push(parseFormulaHelper(c[i], depth, collapsed));
                            }
                            content = {text: json.name + "(" + seqs.join(", ") + ")", type: 'term'};
                            break;
                    }
                    items.push(span(json.id, content, depth));
                } else {
                    items.push(span(json.id, {text: json.name, type: 'symbol'}, depth));
                }
                return items.join("");
            }

            scope.formulaAxiomsMap = {};

            scope.formulaClick = function(formulaId, event) {
              // avoid event propagation to parent span (otherwise: multiple calls with a single click since nested spans)
              event.stopPropagation();
              scope.onTactic({formulaId: formulaId, tacticId: "StepAt"});
            }

            scope.formulaRightClick = function(formulaId, event) {
              event.stopPropagation();
              if (scope.formulaAxiomsMap[formulaId] === undefined) {
                // axioms not fetched yet
                $http.get('proofs/user/' + scope.userId + '/' + scope.proofId + '/' + scope.nodeId + '/' + formulaId + '/list')
                  .success(function(data) {
                    scope.formulaAxiomsMap[formulaId] = $.map(data, function(info, i) {
                      return convertTacticInfo(info);
                    });
                    scope.tacticPopover.open(formulaId);
                });
              } else {
                // tactic already cached
                scope.tacticPopover.open(formulaId);
              }
            }

            scope.applyTactic = function(formulaId, tacticId) {
              scope.tacticPopover.close();
              scope.onTactic({formulaId: formulaId, tacticId: tacticId});
            }

            scope.applyInputTactic = function(formulaId, tactic) {
              scope.tacticPopover.close();
              //@note have to declare local variables with exactly the names of the event arguments,
              //      otherwise the event parameters are undefined in the listener :-O
              var tacticId = tactic.id;
              var input = tactic.derivation.input;
              scope.onInputTactic({formulaId: formulaId, tacticId: tacticId, input: input});
            }

            scope.input = function(formula, tactic) {
              return $.grep(tactic.derivation.input, function(elem, i) { return elem.param === formula; })[0].value;
            }

            scope.tacticPopover = {
              openFormulaId: undefined,
              isOpen: function(formulaId) { return scope.tacticPopover.openFormulaId !== undefined && scope.tacticPopover.openFormulaId === formulaId; },
              open: function(formulaId) { scope.tacticPopover.openFormulaId = formulaId; },
              formulaId: function() { return scope.tacticPopover.openFormulaId; },
              close: function() { scope.tacticPopover.openFormulaId = undefined; }
            }

            scope.dndTooltip = {
              openFormulaId: undefined,
              data: undefined,
              isOpen: function(formulaId) { return scope.dndTooltip.openFormulaId !== undefined && scope.dndTooltip.openFormulaId === formulaId; },
              open: function(formulaId) { scope.dndTooltip.openFormulaId = formulaId; },
              formulaId: function() { return scope.dndTooltip.openFormulaId; },
              close: function() { scope.dndTooltip.openFormulaId = undefined; }
            }

            scope.dndSink = function(sinkFormulaId) {
              return {
                formulaDrop: function(dragData) {
                  scope.dndTooltip.close();
                  if (sinkFormulaId !== dragData) {
                    var fml1Id = dragData;
                    var fml2Id = sinkFormulaId;
                    scope.onTwoPositionTactic({fml1Id: fml1Id, fml2Id: fml2Id, tacticId: 'step'});
                  }
                },
                formulaDragEnter: function(dragData) {
                  if (sinkFormulaId !== dragData) {
                    $http.get('proofs/user/' + scope.userId + '/' + scope.proofId + '/' + scope.nodeId + '/' + dragData + '/' + sinkFormulaId + '/twoposlist')
                      .success(function(data) {
                        if (data.length > 0) {
                          var tactic = data[0];
                          if (tactic.derivation !== undefined && tactic.derivation.type === 'sequentrule') {
                            //@todo open a popover if input is required
                            scope.dndTooltip.data = convertSequentRuleToInput(tactic);
                          } else {
                            scope.dndTooltip.data = tactic;
                          }
                        } else {
                          scope.dndTooltip.data = undefined;
                        }
                        scope.dndTooltip.open(sinkFormulaId);
                      });
                  }
                },
                formulaDragLeave: function(dragData) {
                  scope.dndTooltip.close();
                }
              }
            }

            convertTacticInfo = function(info) {
              info.standardDerivation = convertTactic(info.standardDerivation);
              if (info.comfortDerivation !== undefined) {
                info.comfortDerivation = convertTactic(info.comfortDerivation);
              }
              info.selectedDerivation = function() {
                return this.reduceBranching ? this.comfortDerivation : this.standardDerivation;
              }
              // reduce branching by default
              info.reduceBranching = info.comfortDerivation !== undefined;
              info.isOpen = (info.selectedDerivation().derivation.input !== undefined &&
                info.selectedDerivation().derivation.input !== null &&
                info.selectedDerivation().derivation.input.length > 0);
              return info;
            }

            convertTactic = function(tactic) {
              if (tactic.derivation.type === 'sequentrule') {
                return convertSequentRuleToInput(tactic);
              } else if (tactic.derivation.type === 'axiom') {
                return tactic;
              } else if (tactic.derivation.type === 'tactic') {
                return tactic;
              } else {
                console.log("Unknown deduction type '" + tactic.derivation.type + "'");
              }
            }

            convertSequentRuleToInput = function(tactic) {
              tactic.derivation.premise = $.map(tactic.derivation.premise, function(premise, i) {
                return {ante: convertToInput(premise.ante, tactic), succ: convertToInput(premise.succ, tactic), isClosed: premise.isClosed};
              });
              return tactic;
            }

            convertToInput = function(formulas, tactic) {
              //@note double-wrap array so that it doesn't get flattened
              return $.map(formulas, function(formula, i) { return [convertFormulaToInput(formula, tactic)]; });
            }

            convertFormulaToInput = function(formula, tactic) {
              var result = [];
              if (tactic.derivation.input === undefined || tactic.derivation.input === null) {
                tactic.derivation.input = [];
              }
              var inputs = $.grep(tactic.derivation.input, function(input, i) { return formula.indexOf(input.param) >= 0; });
              var inputBoundaries = $.map(inputs, function(input, i) {
                var inputStart = formula.indexOf(input.param);
                return {start: inputStart, end: inputStart + input.param.length };
              }).sort(function(a, b) { a.start <= b.start ? -1 : 1; });

              if (inputBoundaries.length > 0) {
                result[0] = {text: formula.slice(0, inputBoundaries[0].start), isInput: false};
                result[1] = createInput(formula, tactic, inputBoundaries[0]);
                for (var i = 1; i < inputBoundaries.length; i++) {
                  result[i+1] = {text: formula.slice(inputBoundaries[i-1].end, inputBoundaries[i].start), isInput: false};
                  result[i+2] = createInput(formula, tactic, inputBoundaries[i]);
                }
                result[inputBoundaries.length+1] = {
                  text: formula.slice(inputBoundaries[inputBoundaries.length-1].end, formula.length),
                  isInput: false
                }
              } else {
                result[0] = {text: formula, isInput: false};
              }
              return result;
            }

            createInput = function(formula, tactic, inputBoundary) {
              var inputId = formula.slice(inputBoundary.start, inputBoundary.end);
              return {
                text: inputId,
                isInput: true,
                placeholder: inputId,
                value: function(newValue) {
                  //@note check arguments.length to determine if we're called as getter or as setter
                  return arguments.length ?
                    ($.grep(tactic.derivation.input, function(elem, i) { return elem.param === inputId; })[0].value = newValue) :
                     $.grep(tactic.derivation.input, function(elem, i) { return elem.param === inputId; })[0].value;
                }
              };
            }

            var fmlMarkup = parseFormulaHelper(scope.formula, 0, scope.collapsed);
            // compile template, bind to scope, and add into DOM
            if (scope.collapsed) {
              //@note if collapsed we don't have any listeners, no need to compile
              element.append('<span>' + fmlMarkup + '</span>');
            } else {
              var template = '<span ng-class="{\'k4-abbreviate\': collapsed}">' + fmlMarkup + '</span>';
              element.append($compile(template)(scope));
            }

        }
    };
  }]);
