;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns jibe.halite.halite-docs
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [jibe.halite-guide :as halite-guide]
            [jibe.lib.fixed-decimal :as fixed-decimal]
            [jibe.lib.format-errors :as format-errors]
            [jibe.logic.expression :as expression]
            [jibe.logic.jadeite :as jadeite]
            [jibe.logic.resource-spec-construct :as resource-spec-construct :refer [workspace spec variables constraints refinements]])
  (:import [jibe.halite_guide HCInfo]
           [net.nextencia.rrdiagram.grammar.model GrammarToRRDiagram BNFToGrammar]
           [net.nextencia.rrdiagram.grammar.rrdiagram RRDiagramToSVG]))

(set! *warn-on-reflection* true)

;; TODO:
;; define jadeite operator precedence
;; specify use of parens and {} in jadeite

(comment
  ;; an example of evaluating a halite form in the context of a spec-map
  (let [r (halite-guide/hc-body {:spec/A {:spec-vars {:x "Integer"}
                                          :constraints [["c" '(> x 12)]]
                                          :refines-to {:spec/B {:name "r"
                                                                :expr '{:$type :spec/B
                                                                        :a (inc x)}}}}
                                 :spec/B {:abstract? true
                                          :spec-vars {:a "Integer"}
                                          :constraints []
                                          :refines-to {}}}
                                '(refine-to {:$type :spec/A
                                             :x 100} :spec/B))]
    (.-h-result r)))

(defn expand-example [[op m]]
  [op (if (:examples m)
        (assoc m :examples (mapv (fn [example]
                                   (let [{:keys [expr-str expr-str-j result result-j workspace-f instance spec-map]} example]
                                     (if expr-str
                                       (let [{:keys [h-result j-result j-expr]}
                                             (if spec-map
                                               (let [h-expr (edn/read-string
                                                             {:readers {'d fixed-decimal/fixed-decimal-reader}}
                                                             expr-str)
                                                     ^HCInfo i (halite-guide/hc-body
                                                                spec-map
                                                                h-expr)]
                                                 {:h-result (.-h-result i)
                                                  :j-result (.-j-result i)
                                                  :j-expr (jadeite/to-jadeite h-expr)})
                                               (if workspace-f
                                                 (let [workspace (workspace-f expr-str)
                                                       ^HCInfo i (halite-guide/hc-body
                                                                  [workspace]
                                                                  :my
                                                                  (list 'get
                                                                        (list 'refine-to instance :my/Result$v1)
                                                                        :x))]
                                                   {:h-result (.-h-result i)
                                                    :j-result (.-j-result i)
                                                    :j-expr (jadeite/to-jadeite (edn/read-string
                                                                                 {:readers {'d fixed-decimal/fixed-decimal-reader}}
                                                                                 expr-str))})
                                                 (let [i (halite-guide/h*
                                                          (edn/read-string
                                                           {:readers {'d fixed-decimal/fixed-decimal-reader}}
                                                           expr-str)
                                                          true)]
                                                   {:h-result (.-h-result i)
                                                    :j-result (.-j-result i)
                                                    :j-expr (.-j-expr i)})))

                                             err-result? (and (vector? h-result)
                                                              (= :throws (first h-result)))
                                             to-merge (apply merge [(when (= expr-str-j :auto)
                                                                      {:expr-str-j j-expr})
                                                                    (when (= result :auto)
                                                                      (if err-result?
                                                                        {:err-result (str (namespace (get h-result 2))
                                                                                          "/"
                                                                                          (name (get h-result 2)))}
                                                                        {:result (pr-str h-result)}))
                                                                    (when (or (= result-j :auto)
                                                                              (and expr-str-j
                                                                                   (not (contains? example :result-j))))
                                                                      (if err-result?
                                                                        {:err-result-j (str (namespace (get h-result 2))
                                                                                            "/"
                                                                                            (name (get h-result 2)))}
                                                                        {:result-j j-result}))])
                                             base-example (if (contains? to-merge :err-result)
                                                            (dissoc example :result)
                                                            example)
                                             base-example (if (contains? to-merge :err-result-j)
                                                            (dissoc base-example :result-j)
                                                            base-example)]
                                         (merge base-example to-merge))
                                       example)))
                                 (:examples m)))
        m)])

(defn expand-examples-map [op-maps]
  (->> op-maps
       (mapcat expand-example)
       (apply sorted-map)))

(defn expand-examples-vector [basic-bnf]
  (->> basic-bnf
       (partition 2)
       (mapcat expand-example)
       vec))

;;;;

(def misc-notes ["'whitespace' refers to characters such as spaces, tabs, and newlines."
                 "Whitespace is generally not called out in the following diagrams. However, it is specified for a few syntactic constructs that explicitly rely on whitespace."])

(def misc-notes-halite ["For halite, whitespace also includes the comma. The comma can be used as an optional delimiter in sequences to improve readability."])

(def basic-bnf (expand-examples-vector
                ['non-numeric-character {:bnf "'A-Z' | 'a-z' | '*' | '!' | '$' | '=' | '<' | '>' | '_' | '.' | '?'"}
                 'plus-minus-character {:bnf "'+' | '-'"}
                 'symbol-character {:bnf "non-numeric-character | plus-minus-character | '0-9'"}
                 'bare-symbol {:bnf "plus-minus-character | ((non-numeric-character | plus-minus-character) [{symbol-character}])"}
                 'symbol {:bnf "bare-symbol [ '/' bare-symbol]"
                          :bnf-j "(bare-symbol [ '/' bare-symbol]) | ('’' bare-symbol [ '/' bare-symbol] '’')"
                          :doc "Symbols are identifiers that allow values and operations to be named. The following are reserved and cannot be used as user defined symbols: true, false, nil."
                          :comment "Symbols are used to identify operators, variables in expressions, and specifications."
                          :comment-j "Symbols are used to identify operators, variables in expressions, specifications, and fields within specifications."
                          :comment-2 "Symbols are not values. There are no expressions that produce symbols. Anywhere that a symbol is called for in an operator argument list, a literal symbol must be provided. Symbols passed as arguments to operators are not evaluated. Symbols used within expressions in general are evaluated prior to invoking the operator."
                          :comment-3 "A common pattern in operator arguments is to provide a sequence of alternating symbols and values within square brackets. In these cases each symbol is bound to the corresponding value in pair-wise fashion."
                          :comment-3-j "The following are also legal symbols, but they are reserved for system use: &&, ||, /, %, |"
                          :throws ['h-err/invalid-symbol-char
                                   'h-err/invalid-symbol-length]
                          :examples [{:expr-str "a"
                                      :expr-str-j "a"}
                                     {:expr-str "a.b"
                                      :expr-str-j "a.b"}
                                     {:expr-str "a/b"
                                      :expr-str-j :auto}]
                          :tags #{}
                          :tags-j #{'spec-id-op 'instance-field-op 'instance-op 'instance-out}}
                 'keyword {:bnf "':' symbol"
                           :bnf-j nil
                           :doc "Keywords are identifiers that are used for instance field names. The following are reserved and cannot be used as user defined keywords: :true, :false, :nil."
                           :comment "Keywords are not values. There are no expressions that produce keywords. Anywhere that a keyword is called for in an operator arugment list, a literal keyword must be provided. Keywords themselves cannot be evaluated."
                           :examples [{:expr-str ":age"
                                       :expr-str-j :auto}
                                      {:expr-str ":x/y"
                                       :expr-str-j :auto}]
                           :tags #{'spec-id-op 'instance-field-op 'instance-op 'instance-out}
                           :tags-j #{}
                           :throws ['h-err/invalid-keyword-char
                                    'h-err/invalid-keyword-length]}

                 'boolean {:bnf "true | false"
                           :tags #{'boolean-op 'boolean-out}}
                 'string {:bnf " '\"' {char | '\\' ('\\' | '\"' | 't' | 'n' | ('u' hex-digit hex-digit hex-digit hex-digit))} '\"'"
                          :doc "Strings are sequences of characters. Strings can be multi-line. Quotation marks can be included if escaped with a \\. A backslash can be included with the character sequence: \\\\ . Strings can include special characters, e.g. \\t for a tab and \\n for a newline, as well as unicode via \\uNNNN. Unicode can also be directly entered in strings. Additional character representations may work but the only representations that are guaranteed to work are those documented here."
                          :examples [{:expr-str "\"\""
                                      :expr-str-j :auto}
                                     {:expr-str "\"hello\""
                                      :expr-str-j :auto}
                                     {:expr-str "\"say \\\"hi\\\" now\""
                                      :expr-str-j :auto
                                      :result :auto}
                                     {:expr-str "\"one \\\\ two\""
                                      :expr-str-j :auto
                                      :result :auto}
                                     {:expr-str "\"\\t\\n\""
                                      :expr-str-j :auto
                                      :result :auto}
                                     {:expr-str "\"☺\""
                                      :expr-str-j :auto
                                      :result :auto}
                                     {:expr-str "\"\\u263A\""
                                      :expr-str-j "\"\\u263A\""
                                      :result :auto}]
                          :tags #{'string-op}
                          :throws ['h-err/size-exceeded]}
                 'integer {:bnf "[plus-minus-character] '0-9' {'0-9'}"
                           :doc "Signed, eight byte numeric integer values. Alternative integer representations may work, but the only representation that is guaranteed to work on an ongoing basis is that documented here. The largest positive integer is 9223372036854775807. The most negative integer is -9223372036854775808."
                           :examples [{:expr-str "0"
                                       :expr-str-j :auto}
                                      {:expr-str "1"
                                       :expr-str-j :auto}
                                      {:expr-str "+1"
                                       :expr-str-j :auto}
                                      {:expr-str "-1"
                                       :expr-str-j :auto}
                                      {:expr-str "9223372036854775807"
                                       :expr-str-j :auto}
                                      {:expr-str "-9223372036854775808"
                                       :expr-str-j :auto}
                                      {:expr-str "-0"
                                       :expr-str-j :auto}]
                           :tags #{'integer-out 'integer-op}}

                 'fixed-decimal {:bnf "'#' 'd' [whitespace] '\"' ['-'] ('0' | ('1-9' {'0-9'})) '.' '0-9' {'0-9'} '\"'"
                                 :doc "Signed numeric values with decimal places. The scale (i.e. the number of digits to the right of the decimal place), must be between one and 18. Conceptually, the entire numeric value must fit into the same number of bytes as an 'integer'. So the largest fixed-decimal value with a scale of one is: #d \"922337203685477580.7\", and the most negative value with a scale of one is: #d \"-922337203685477580.8\". Similarly, the largest fixed-decimal value with a scale of 18 is: #d \"9.223372036854775807\" and the most negative value with a scale of 18 is: #d \"-9.223372036854775808\". The scale of the fixed-decimal value can be set to what is needed, but as more precision is added to the right of the decimal place, fewer digits are available to the left of the decimal place."
                                 :examples [{:expr-str "#d \"1.1\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"-1.1\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"1.00\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"0.00\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"922337203685477580.7\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"-922337203685477580.8\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"9.223372036854775807\""
                                             :expr-str-j :auto}
                                            {:expr-str "#d \"-9.223372036854775808\""
                                             :expr-str-j :auto}]
                                 :tags #{'fixed-decimal-op 'fixed-decimal-out}}

                 'instance {:bnf "'{' ':$type' keyword:spec-id {keyword value} '}' "
                            :bnf-j "'{' '$type' ':' symbol:spec-id {',' symbol ':' value } '}'"
                            :doc "Represents an instance of a specification."
                            :comment "The contents of the instance are specified in pair-wise fashion with alternating field names and field values."
                            :comment-2 "The special field name ':$type' is mandatory but cannot be used as the other fields are."
                            :comment-2-j "The special field name '$type' is mandatory but cannot be used as the other fields are."
                            :examples [{:expr-str "{:$type :text/Spec$v1 :x 1 :y -1}"
                                        :expr-str-j "{$type: my/Spec$v1, x: 1, y: -1}"}]
                            :throws ['h-err/no-abstract
                                     'h-err/resource-spec-not-found
                                     'h-err/missing-type-field
                                     'h-err/missing-required-vars
                                     'h-err/invalid-instance
                                     'h-err/field-value-of-wrong-type
                                     'h-err/field-name-not-in-spec
                                     'h-err/invalid-type-value
                                     'h-err/not-boolean-constraint ;; is this right?
                                     ]
                            :tags #{'instance-out 'instance-op 'instance-field-op}}
                 'vector {:bnf "'[' [whitespace] { value whitespace} [value] [whitespace] ']'"
                          :bnf-j "'[' [whitespace] [value] [whitespace] {',' [whitespace] value [whitespace]} [whitespace]']'"
                          :doc "A collection of values in a prescribed sequence."
                          :examples [{:expr-str "[]"
                                      :expr-str-j :auto}
                                     {:expr-str "[1 2 3]"
                                      :expr-str-j :auto}
                                     {:expr-str "[#{1 2} #{3}]"
                                      :expr-str-j :auto}]
                          :throws ['h-err/literal-must-evaluate-to-value
                                   'h-err/size-exceeded]
                          :tags #{'vector-op 'vector-out}}
                 'set {:bnf "'#' '{' [whitespace] { value [whitespace]} [value] [whitespace] '}'"
                       :bnf-j "'#' '{' [whitespace] [value] [whitespace] {',' [whitespace] value [whitespace]} '}'"
                       :doc "A collection of values in an unordered set. Duplicates are not allowed."
                       :comment "The members of sets are not directly accessible. If it is necessary to access the members of a set, it is recommended to design the data structures going into the sets in such a way that the set can be sorted into a vector for access."
                       :examples [{:expr-str "#{}"
                                   :expr-str-j :auto}
                                  {:expr-str "#{1 2 3}"
                                   :expr-str-j :auto}
                                  {:expr-str "#{[1 2] [3]}"
                                   :expr-str-j :auto}]
                       :throws ['h-err/literal-must-evaluate-to-value
                                'h-err/size-exceeded]
                       :tags #{'set-op 'set-out}}
                 'value {:bnf "boolean | string | integer | fixed-decimal | instance | vector | set"
                         :doc "Expressions and many literals produce values."}
                 'unset {:bnf "unset"
                         :doc "A pseudo-value that represents the lack of a value."}
                 'nothing {:bnf "nothing"
                           :doc "The absence of a value."}
                 'any {:bnf "value | unset"
                       :doc "Refers to either the presence of absence of a value, or a pseudo-value indicating the lack of a value."}]))

(defn make-workspace-fn [workspace]
  (fn [expr-str] (update-in workspace
                            [:specs
                             0
                             :jibe.data.model/spec-refinements
                             0
                             :jibe.data.model/refinement-e]
                            (fn [_] (expression/parse :halite
                                                      (string/replace
                                                       "{:$type :my/Result$v1, :x <expr>}"
                                                       "<expr>"
                                                       expr-str))))))

(def op-maps
  (expand-examples-map
   {'$no-value {:sigs [["" "unset"]]
                :sigs-j [["'$no-value'" "unset"]]
                :tags #{:optional-out}
                :doc "Constant that produces the special 'unset' value which represents the lack of a value."
                :comment "Expected use is in an instance expression to indicate that a field in the instance does not have a value. However, it is suggested that alternatives include simply omitting the field name from the instance or using a variant of a 'when' expression to optionally produce a value for the field."
                :basic-ref ['unset]
                :see-also ['when 'when-value 'when-value-let]}
    '$this {:sigs [["" "value"]]
            :sigs-j [["<$this>" "unset"]]
            :doc "Context dependent reference to the containing object."}
    '* {:sigs [["integer integer" "integer"]
               ["fixed-decimal integer" "fixed-decimal"]]
        :sigs-j [["integer '*' integer" "integer"]
                 ["fixed-decimal '*' integer" "fixed-decimal"]]
        :tags #{:integer-op :integer-out :fixed-decimal-op :fixed-decimal-out}
        :basic-ref ['integer 'fixed-decimal]
        :doc "Multiply two numbers together."
        :comment "Note that fixed-decimal values cannot be multiplied together. Rather the multiplication operator is used to scale a fixed-decimal value within the number space of a given scale of fixed-decimal. This can also be used to effectively convert an arbitrary integer value into a fixed-decimal number space by multiplying the integer by unity in the fixed-decimal number space of the desired scale."
        :throws ['h-err/overflow]
        :examples [{:expr-str "(* 2 3)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(* #d \"2.2\" 3)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(* 2 3 4)"
                    :expr-str-j :auto
                    :result :auto}]}
    '+ {:sigs [["integer integer {integer}" "integer"]
               ["fixed-decimal fixed-decimal {fixed-decimal}" "fixed-decimal"]]
        :sigs-j [["integer '+' integer" "integer"]
                 ["fixed-decimal '+' fixed-decimal" "fixed-decimal"]]
        :tags #{:integer-op :integer-out :fixed-decimal-op :fixed-decimal-out}
        :basic-ref ['integer 'fixed-decimal]
        :doc "Add two numbers together."
        :throws ['h-err/overflow]
        :examples [{:expr-str "(+ 2 3)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(+ #d \"2.2\" #d \"3.3\")"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(+ 2 3 4)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(+ 2 -3)"
                    :expr-str-j :auto
                    :result :auto}]}
    '- {:sigs [["integer integer {integer}" "integer"]
               ["fixed-decimal fixed-decimal {fixed-decimal}" "fixed-decimal"]]
        :sigs-j [["integer '-' integer" "integer"]
                 ["fixed-decimal '-' fixed-decimal" "fixed-decimal"]]
        :tags #{:integer-op :integer-out :fixed-decimal-op :fixed-decimal-out}
        :basic-ref ['integer 'fixed-decimal]
        :doc "Subtract one number from another."
        :examples [{:expr-str "(- 2 3)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(- #d \"2.2\" #d \"3.3\")"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(- 2 3 4)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(- 2 -3)"
                    :expr-str-j :auto
                    :result :auto}]
        :throws ['h-err/overflow]}
    '< {:sigs [["((integer integer) | (fixed-decimal fixed-decimal))" "boolean"]]
        :sigs-j [["((integer '<'  integer) | (fixed-decimal '<' fixed-decimal))" "boolean"]]
        :tags #{:integer-op :fixed-decimal-op :boolean-out}
        :basic-ref ['integer 'fixed-decimal 'boolean]
        :doc "Determine if a number is strictly less than another."
        :examples [{:expr-str "(< 2 3)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(< #d \"2.2\" #d \"3.3\")"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(< 2 2)"
                    :expr-str-j :auto
                    :result :auto}]}
    '<= {:sigs [["((integer integer) | (fixed-decimal fixed-decimal))" "boolean"]]
         :sigs-j [["((integer '<=' integer) | (fixed-decimal '<=' fixed-decimal))" "boolean"]]
         :tags #{:integer-op :fixed-decimal-op :boolean-out}
         :basic-ref ['integer 'fixed-decimal 'boolean]
         :doc "Determine if a number is less than or equal to another."
         :examples [{:expr-str "(<= 2 3)"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(<= #d \"2.2\" #d \"3.3\")"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(<= 2 2)"
                     :expr-str-j :auto
                     :result :auto}]}
    '= {:sigs [["value value {value}" "boolean"]]
        :sigs-j [["'equalTo' '(' value ',' value {',' value} ')'" "boolean"]
                 ["value '==' value" "boolean"]]
        :tags #{:integer-op :fixed-decimal-op :set-op :vector-op :boolean-out :boolean-op :instance-op}
        :basic-ref ['value 'boolean]
        :doc "Determine if values are equivalent. For vectors and sets this performs a comparison of their contents."
        :throws ['l-err/result-always-known]
        :see-also ['= 'not=]
        :examples [{:expr-str "(= 2 2)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= #d \"2.2\" #d \"3.3\")"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= 2 3)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= 1 1 1)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= 1 1 2)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= \"hi\" \"hi\")"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= [1 2 3] [1 2 3])"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= [1 2 3] #{1 2 3})"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= #{3 1 2} #{1 2 3})"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= [#{1 2} #{3}] [#{1 2} #{3}])"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(= [#{1 2} #{3}] [#{1 2} #{4}])"
                    :expr-str-j :auto
                    :result :auto}
                   {:workspace-f (make-workspace-fn (workspace :my
                                                               {:my/Spec []
                                                                :my/Result []}
                                                               (spec :Result :concrete
                                                                     (variables [:x "Boolean"])
                                                                     (refinements
                                                                      [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                               (spec :Other :concrete)
                                                               (spec :Spec :concrete
                                                                     (variables [:x "Integer"]
                                                                                [:y "Integer"]))))
                    :instance {:$type :my/Other$v1}
                    :expr-str "(= {:$type :my/Spec$v1 :x 1 :y -1} {:$type :my/Spec$v1 :x 1 :y 0})"
                    :expr-str-j :auto
                    :result :auto}
                   {:workspace-f (make-workspace-fn (workspace :my
                                                               {:my/Spec []
                                                                :my/Result []}
                                                               (spec :Result :concrete
                                                                     (variables [:x "Boolean"])
                                                                     (refinements
                                                                      [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                               (spec :Other :concrete)
                                                               (spec :Spec :concrete
                                                                     (variables [:x "Integer"]
                                                                                [:y "Integer"]))))
                    :instance {:$type :my/Other$v1}
                    :expr-str "(= {:$type :my/Spec$v1 :x 1 :y 0} {:$type :my/Spec$v1 :x 1 :y 0})"
                    :expr-str-j :auto
                    :result :auto}]}
    '=> {:sigs [["boolean boolean" "boolean"]]
         :sigs-j [["boolean '=>' boolean" "boolean"]]
         :tags #{:boolean-op :boolean-out}
         :basic-ref ['boolean]
         :doc "Performs logical implication. If the first value is true, then the second value must also be true for the result to be true. If the first value is false, then the result is true."
         :examples [{:expr-str "(=> (> 2 1) (< 1 2))"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(=> (> 2 1) (> 1 2))"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(=> (> 1 2) false)"
                     :expr-str-j :auto
                     :result :auto}]
         :see-also ['and 'every? 'not 'or]}
    '> {:sigs [["((integer integer) | (fixed-decimal fixed-decimal))" "boolean"]]
        :sigs-j [["((integer '>'  integer) | (fixed-decimal '>' fixed-decimal))" "boolean"]]
        :tags #{:integer-op :fixed-decimal-op :boolean-out}
        :basic-ref ['integer 'fixed-decimal 'boolean]
        :doc "Determine if a number is strictly greater than another."
        :examples [{:expr-str "(> 3 2)"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(> #d \"3.3\" #d \"2.2\" )"
                    :expr-str-j :auto
                    :result :auto}
                   {:expr-str "(> 2 2)"
                    :expr-str-j :auto
                    :result :auto}]}
    '>= {:sigs [["((integer integer) | (fixed-decimal fixed-decimal))" "boolean"]]
         :sigs-j [["((integer '>='  integer) | (fixed-decimal '>=' fixed-decimal))" "boolean"]]
         :tags #{:integer-op :fixed-decimal-op :boolean-out}
         :basic-ref ['integer 'fixed-decimal 'boolean]
         :doc "Determine if a number is greater than or equal to another."
         :examples [{:expr-str "(>= 3 2)"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(>= #d \"3.3\" #d \"2.2\" )"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(>= 2 2)"
                     :expr-str-j :auto
                     :result :auto}]}
    'abs {:sigs [["integer" "integer"]
                 ["fixed-decimal" "fixed-decimal"]]
          :sigs-j [["'abs' '(' integer ')'" "integer"]
                   ["'abs' '(' fixed-decimal ')'" "fixed-decimal"]]
          :tags #{:integer-op :integer-out :fixed-decimal-op :fixed-decimal-out}
          :basic-ref ['integer 'fixed-decimal]
          :doc "Compute the absolute value of a number."
          :comment "Since the negative number space contains one more value than the positive number space, it is a runtime error to attempt to take the absolute value of the most negative value for a given number space."
          :throws ['h-err/abs-failure]
          :examples [{:expr-str "(abs -1)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(abs 1)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(abs #d \"-1.0\")"
                      :expr-str-j :auto
                      :result :auto}]}
    'and {:sigs [["boolean boolean {boolean}" "boolean"]]
          :sigs-j [["boolean '&&' boolean" "boolean"]]
          :tags #{:boolean-op :boolean-out}
          :basic-ref ['boolean]
          :doc "Perform a logical 'and' operation on the input values."
          :comment "The operation does not short-circuit. Even if the first argument evaluates to false the other arguments are still evaluated."
          :see-also ['=> 'every? 'not 'or]
          :examples [{:expr-str "(and true false)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(and true true)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(and (> 2 1) (> 3 2) (> 4 3))"
                      :expr-str-j :auto
                      :result :auto}]}
    'any? {:sigs [["'[' symbol (set | vector) ']' boolean-expression" "boolean"]]
           :sigs-j [["'any?' '(' symbol 'in' (set | vector) ')' boolean-expression" "boolean"]]
           :tags #{:set-op :vector-op :boolean-out :special-form}
           :basic-ref ['set 'vector 'boolean]
           :doc "Evaluates to true if the boolean-expression is true when the symbol is bound to some element in the collection."
           :comment "The operation does not short-circuit. The boolean-expression is evaluated for all elements even if a prior element has caused the boolean-expression to evaluate to true. Operating on an empty collection produces a false value."
           :examples [{:expr-str "(any? [x [1 2 3]] (> x 1))"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(any? [x #{1 2 3}] (> x 10))"
                       :expr-str-j :auto
                       :result :auto}]
           :throws ['h-err/comprehend-binding-wrong-count
                    'h-err/comprehend-collection-invalid-type
                    'l-err/binding-target-invalid-symbol
                    'h-err/binding-target-must-be-bare-symbol
                    'h-err/not-boolean-body]
           :see-also ['every? 'or]}
    'concat {:sigs [["vector vector" "vector"]
                    ["(set (set | vector))" "set"]]
             :sigs-j [["vector '.' 'concat' '('  vector ')'" "vector"]
                      ["(set '.' 'concat' '(' (set | vector) ')')" "set"]]
             :tags #{:set-op :vector-op :vector-out :set-out}
             :basic-ref ['vector 'set]
             :doc "Combine two collections into one."
             :comment "Invoking this operation with a vector and an empty set has the effect of converting a vector into a set with duplicate values removed."
             :examples [{:expr-str "(concat [1 2] [3])"
                         :expr-str-j :auto
                         :result :auto}
                        {:expr-str "(concat #{1 2} [3 4])"
                         :expr-str-j :auto
                         :result :auto}
                        {:expr-str "(concat [] [])"
                         :expr-str-j :auto
                         :result :auto}]
             :throws ['h-err/not-both-vectors
                      'h-err/size-exceeded]}
    'conj {:sigs [["set value {value}" "set"]
                  ["vector value {value}" "vector"]]
           :sigs-j [["set '.' 'conj' '(' value {',' value} ')'" "set"]
                    ["vector '.' 'conj' '(' value {',' value} ')'" "vector"]]
           :tags #{:set-op :vector-op :set-out :vector-out}
           :basic-ref ['vector 'set 'value]
           :doc "Add individual items to a collection."
           :comment "Only definite values may be put into collections, i.e. collections cannot contain 'unset' values."
           :examples [{:expr-str "(conj [1 2] 3)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(conj #{1 2} 3 4)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(conj [] 1)"
                       :expr-str-j :auto
                       :result :auto}]
           :throws ['h-err/argument-not-set-or-vector
                    'h-err/cannot-conj-unset
                    'h-err/size-exceeded]}
    'contains? {:sigs [["set value" "boolean"]]
                :sigs-j [["set '.' 'contains?' '(' value ')'" "boolean"]]
                :tags #{:set-op :boolean-out}
                :basic-ref ['set 'value 'boolean]
                :doc "Determine if a specific value is in a set."
                :comment "Since collections themselves are compared by their contents, this works for collections nested inside of sets."
                :examples [{:expr-str "(contains? #{\"a\" \"b\"} \"a\")"
                            :expr-str-j :auto
                            :result :auto}
                           {:expr-str "(contains? #{\"a\" \"b\"} \"c\")"
                            :expr-str-j :auto
                            :result :auto}
                           {:expr-str "(contains? #{#{1 2} #{3}} #{1 2})"
                            :expr-str-j :auto
                            :result :auto}
                           {:expr-str "(contains? #{[1 2] [3]} [4])"
                            :expr-str-j :auto
                            :result :auto}]}
    'count {:sigs [["(set | vector)" "integer"]]
            :sigs-j [["(set | vector) '.' 'count()'" "integer"]]
            :tags #{:integer-out :set-op :vector-op}
            :basic-ref ['set 'vector 'integer]
            :doc "Return how many items are in a collection."
            :examples [{:expr-str "(count [10 20 30])"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(count #{\"a\" \"b\"})"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(count [])"
                        :expr-str-j :auto
                        :result :auto}]}
    'dec {:sigs [["integer" "integer"]]
          :sigs-j [["integer '-' '1' " "integer"]]
          :tags #{:integer-op :integer-out}
          :basic-ref ['integer]
          :doc "Decrement a numeric value."
          :throws ['h-err/overflow]
          :see-also ['inc]
          :examples [{:expr-str "(dec 10)"
                      :result :auto}
                     {:expr-str "(dec 0)"
                      :result :auto}]}
    'difference {:sigs [["set set" "set"]]
                 :sigs-j [["set '.' 'difference' '(' set ')'" "set"]]
                 :tags #{:set-op :set-out}
                 :basic-ref ['set]
                 :doc "Compute the set difference of two sets."
                 :comment "This produces a set which contains all of the elements from the first set which do not appear in the second set."
                 :see-also ['intersection 'union 'subset?]
                 :throws ['h-err/arguments-not-sets]
                 :examples [{:expr-str "(difference #{1 2 3} #{1 2})"
                             :expr-str-j :auto
                             :result :auto}
                            {:expr-str "(difference #{1 2 3} #{})"
                             :expr-str-j :auto
                             :result :auto}
                            {:expr-str "(difference #{1 2 3} #{1 2 3 4})"
                             :expr-str-j :auto
                             :result :auto}
                            {:expr-str "(difference #{[1 2] [3]} #{[1 2]})"
                             :expr-str-j :auto
                             :result :auto}]}
    'div {:sigs [["integer integer" "integer"]
                 ["fixed-decimal integer" "fixed-decimal"]]
          :sigs-j [["integer '/' integer" "integer"]
                   ["fixed-decimal '/' integer" "fixed-decimal"]]
          :tags #{:integer-op :integer-out :fixed-decimal-op :fixed-decimal-out}
          :basic-ref ['integer 'fixed-decimal]
          :doc "Divide the first number by the second. When the first argument is an integer the result is truncated to an integer value. When the first argument is a fixed-decimal the result is truncated to the same precision as the first argument."
          :comment "As with multiplication, fixed-decimal values cannot be divided by each other, instead a fixed-decimal value can be scaled down within the number space of that scale."
          :throws ['h-err/divide-by-zero]
          :examples [{:expr-str "(div 12 3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(div #d \"12.3\" 3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(div 14 4)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(div #d \"14.3\" 3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(div 1 0)"
                      :expr-str-j :auto
                      :result :auto}]
          :see-also ['mod]}
    'error {:sigs [["string" "nothing"]]
            :sigs-j [["'error' '(' string ')'" "nothing"]]
            :tags #{:nothing-out}
            :basic-ref ['nothing]
            :doc "Produce a runtime error with the provided string as an error message."
            :comment "Used to indicate when an unexpected condition has occurred and the data at hand is invalid. It is preferred to use constraints to capture such conditions earlier."
            :examples [{:expr-str "(error \"failure\")"
                        :expr-str-j :auto
                        :result :auto}]
            :throws ['h-err/spec-threw]}
    'every? {:sigs [["'[' symbol (set | vector) ']' boolean-expression" "boolean"]]
             :sigs-j [["'every?' '(' symbol 'in' (set | vector) ')' boolean-expression" "boolean"]]
             :tags #{:set-op :vector-op :boolean-out :special-form}
             :basic-ref ['set 'vector 'boolean]
             :doc "Evaluates to true if the boolean-expression is true when the symbol is bound to each the element in the collection."
             :comment "Does not short-circuit. The boolean-expression is evaluated for all elements, even once a prior element has evaluated to false. Operating on an empty collection produces a true value."
             :examples [{:expr-str "(every? [x [1 2 3]] (> x 0))"
                         :expr-str-j :auto
                         :result :auto}
                        {:expr-str "(every? [x #{1 2 3}] (> x 1))"
                         :expr-str-j :auto
                         :result :auto}]
             :throws ['h-err/comprehend-binding-wrong-count
                      'h-err/comprehend-collection-invalid-type
                      'l-err/binding-target-invalid-symbol
                      'h-err/binding-target-must-be-bare-symbol
                      'h-err/not-boolean-body]
             :see-also ['any? 'and]}
    'expt {:sigs [["integer integer" "integer"]]
           :sigs-j [["'expt' '(' integer ',' integer ')'" "integer"]]
           :tags #{:integer-op :integer-out}
           :basic-ref ['integer]
           :doc "Compute the numeric result of raising the first argument to the power given by the second argument. The exponent argument cannot be negative."
           :examples [{:expr-str "(expt 2 3)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(expt -2 3)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(expt 2 0)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(expt 2 -1)"
                       :expr-str-j :auto
                       :result :auto}]
           :throws ['h-err/overflow
                    'h-err/invalid-exponent]}
    'filter {:sigs [["'[' symbol:element set ']' boolean-expression" "set"]
                    ["'[' symbol:element vector ']' boolean-expression" "vector"]]
             :sigs-j [["'filter' '(' symbol 'in' set ')' boolean-expression" "set"]
                      ["'filter' '(' symbol 'in' vector ')' boolean-expression" "vector"]]
             :tags #{:set-op :vector-op :set-out :vector-out :special-form}
             :basic-ref ['set 'vector 'boolean]
             :doc "Produce a new collection which contains only the elements from the original collection for which the boolean-expression is true. When applied to a vector, the order of the elements in the result preserves the order from the original vector."
             :examples [{:expr-str "(filter [x [1 2 3]] (> x 2))"
                         :expr-str-j :auto
                         :result :auto}
                        {:expr-str "(filter [x #{1 2 3}] (> x 2))"
                         :expr-str-j :auto
                         :result :auto}]
             :throws ['h-err/comprehend-binding-wrong-count
                      'h-err/comprehend-collection-invalid-type
                      'l-err/binding-target-invalid-symbol
                      'h-err/binding-target-must-be-bare-symbol
                      'h-err/not-boolean-body]
             :see-also ['map]}
    'first {:sigs [["vector" "value"]]
            :sigs-j [["vector '.' 'first()'" "value"]]
            :tags #{:vector-op}
            :basic-ref ['vector 'value]
            :doc "Produce the first element from a vector."
            :comment "To avoid runtime errors, if the vector might be empty, use 'count' to check the length first."
            :throws ['h-err/argument-empty
                     'h-err/argument-not-vector]
            :examples [{:expr-str "(first [10 20 30])"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(first [])"
                        :expr-str-j :auto
                        :result :auto}]
            :see-also ['count 'rest]}
    'get {:sigs [["(instance keyword:instance-field)" "any"]
                 ["(vector integer)" "value"]]
          :sigs-j [["(instance '.' symbol:instance-field)" "any"]
                   ["(vector '[' integer ']')" "value"]]
          :tags #{:vector-op :instance-op :optional-out :instance-field-op :special-form}
          :basic-ref ['instance 'vector 'keyword 'integer 'any]
          :basic-ref-j ['instance 'vector 'symbol 'integer 'any]
          :doc "Extract the given item from the first argument. If the first argument is an instance, extract the value for the given field from the given instance. For optional fields, this may produce 'unset'. Otherwise this will always produce a value. If the first argument is a vector, then extract the value at the given index in the vector. The index in this case is zero based."
          :comment "The $type value of an instance is not considered a field that can be extracted with this operator. When dealing with instances of abstract specifications, it is necessary to refine an instance to a given specification before accessing a field of that specification."
          :throws ['h-err/index-out-of-bounds
                   'h-err/field-name-not-in-spec
                   'h-err/invalid-vector-index
                   'h-err/invalid-instance-index]
          :examples [{:expr-str "(get [10 20 30 40] 2)"
                      :expr-str-j :auto
                      :result :auto}
                     {:workspace-f (make-workspace-fn (workspace :my
                                                                 {:my/Spec []
                                                                  :my/SubSpec []
                                                                  :my/Result []}
                                                                 (spec :Result :concrete
                                                                       (variables [:x "Integer"])
                                                                       (refinements
                                                                        [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                 (spec :Other :concrete)
                                                                 (spec :Spec :concrete
                                                                       (variables [:x "Integer"]
                                                                                  [:y "Integer"]))))
                      :instance {:$type :my/Other$v1}
                      :expr-str "(get {:$type :my/Spec$v1, :x -3, :y 2} :x)"
                      :expr-str-j :auto
                      :result :auto}]
          :see-also ['get-in]}
    'get-in {:sigs [["(instance:target | vector:target) '[' (integer | keyword:instance-field) {(integer | keyword:instance-field)} ']'" "any"]]
             :notes ["if the last element of the lookup path is an integer, then the result is a value"
                     "if the last element of the lookup path is an instance field name, then the result is an 'any'; specifically of that last field is the name of an optional field"
                     "the non-terminal field names in the lookup path must be the names of mandatory fields"]
             :sigs-j [["( (instance:target '.' symbol:instance-field) | (vector:target '[' integer ']') ){ ( ('.' symbol:instance-field) | ('[' integer ']' ) ) }"
                       "any"]]
             :tags #{:vector-op :instance-op :optional-out :instance-field-op :special-form}
             :basic-ref ['instance 'vector 'keyword 'integer 'any]
             :basic-ref-j ['instance 'vector 'symbol 'integer 'any]
             :doc "Syntactic sugar for performing the equivalent of a chained series of 'get' operations. The second argument is a vector that represents the logical path to be navigated through the first argument."
             :doc-j "A path of element accessors can be created by chaining together element access forms in sequence."
             :doc-2 "The first path element in the path is looked up in the initial target. If there are more path elements, the next path element is looked up in the result of the first lookup. This is repeated as long as there are more path elements. If this is used to lookup instance fields, then all of the field names must reference mandatory fields unless the field name is the final element of the path. The result will always be a value unless the final path element is a reference to an optional field. In this case, the result may be a value or may be 'unset'."
             :throws ['l-err/get-in-path-empty
                      'h-err/invalid-lookup-target
                      'h-err/field-name-not-in-spec
                      'h-err/index-out-of-bounds
                      'h-err/invalid-vector-index
                      'h-err/invalid-instance-index
                      'h-err/get-in-path-must-be-vector-literal]
             :examples [{:expr-str "(get-in [[10 20] [30 40]] [1 0])"
                         :expr-str-j :auto
                         :result :auto}
                        {:workspace-f (make-workspace-fn (workspace :my
                                                                    {:my/Spec []
                                                                     :my/SubSpec []
                                                                     :my/Result []}
                                                                    (spec :Result :concrete
                                                                          (variables [:x "Integer"])
                                                                          (refinements
                                                                           [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                    (spec :Other :concrete)
                                                                    (spec :Spec :concrete
                                                                          (variables [:x :my/SubSpec$v1]
                                                                                     [:y "Integer"]))
                                                                    (spec :SubSpec :concrete
                                                                          (variables [:a "Integer"]
                                                                                     [:b "Integer"]))))
                         :instance {:$type :my/Other$v1}
                         :expr-str "(get-in {:$type :my/Spec$v1, :x {:$type :my/SubSpec$v1, :a 20, :b 10}, :y 2} [:x :a])"
                         :expr-str-j :auto
                         :result :auto}
                        {:workspace-f (make-workspace-fn (workspace :my
                                                                    {:my/Spec []
                                                                     :my/SubSpec []
                                                                     :my/Result []}
                                                                    (spec :Result :concrete
                                                                          (variables [:x "Integer"])
                                                                          (refinements
                                                                           [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                    (spec :Other :concrete)
                                                                    (spec :Spec :concrete
                                                                          (variables [:x :my/SubSpec$v1]
                                                                                     [:y "Integer"]))
                                                                    (spec :SubSpec :concrete
                                                                          (variables [:a ["Integer"]]
                                                                                     [:b "Integer"]))))
                         :instance {:$type :my/Other$v1}
                         :expr-str "(get-in {:$type :my/Spec$v1, :x {:$type :my/SubSpec$v1, :a [20 30 40], :b 10}, :y 2} [:x :a 1])"
                         :expr-str-j :auto
                         :result :auto}]
             :see-also ['get]}
    'if {:sigs [["boolean any-expression any-expression" "any"]]
         :sigs-j [["'if' '(' boolean ')' any-expression 'else' any-expression" "any"]]
         :tags #{:boolean-op :control-flow :special-form}
         :basic-ref ['boolean 'any]
         :doc "If the first argument is true, then evaluate the second argument, otherwise evaluate the third argument."
         :examples [{:expr-str "(if (> 2 1) 10 -1)"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(if (> 2 1) 10 (error \"fail\"))"
                     :expr-str-j :auto
                     :result :auto}]
         :see-also ['when]}
    'if-value {:sigs [["symbol any-expression any-expression" "any"]]
               :sigs-j [["'ifValue' '(' symbol ')' any-expression 'else' any-expression" "any"]]
               :doc "Consider the value bound to the symbol. If it is a 'value', then evaluate the second argument. If instead it is 'unset' then evaluate the third argument."
               :comment "When an optional instance field needs to be referenced, it is generally necessary to guard the access with either 'if-value' or 'when-value'. In this way, both the case of the field being set and unset are explicitly handled."
               :tags #{:optional-op :control-flow :special-form}
               :basic-ref ['symbol 'any]
               :throws ['h-err/if-value-must-be-bare-symbol
                        'l-err/first-argument-not-optional]
               :see-also ['if-value-let 'when-value]}
    'if-value-let {:sigs [["'[' symbol any:binding ']' any-expression any-expression" "any"]]
                   :sigs-j [["'ifValueLet' '(' symbol '=' any:binding ')'  any-expression 'else' any-expression" "any"]]
                   :basic-ref ['symbol 'any]
                   :tags #{:optional-op :control-flow :special-form}
                   :doc "If the binding value is a 'value' then evaluate the second argument with the symbol bound to binding. If instead, the binding value is 'unset', then evaluate the third argument without introducing a new binding for the symbol."
                   :comment "This is similar to the 'if-value' operation, but applies generally to an expression which may or may not produce a value."
                   :examples [{:workspace-f (make-workspace-fn (workspace :my
                                                                          {:my/Spec []
                                                                           :my/Result []}
                                                                          (spec :Result :concrete
                                                                                (variables [:x "Integer"])
                                                                                (refinements
                                                                                 [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                          (spec :Other :concrete)
                                                                          (spec :Spec :concrete
                                                                                (variables [:p "Integer"]
                                                                                           [:n "Integer"]
                                                                                           [:o "Integer" :optional])
                                                                                (constraints [:pc [:halite "(> p 0)"]]
                                                                                             [:pn [:halite "(< n 0)"]]))))
                               :instance {:$type :my/Other$v1}
                               :expr-str "(if-value-let [x (when (> 2 1) 19)] (inc x) 0)"
                               :expr-str-j :auto
                               :result :auto}
                              {:workspace-f (make-workspace-fn (workspace :my
                                                                          {:my/Spec []
                                                                           :my/Result []}
                                                                          (spec :Result :concrete
                                                                                (variables [:x "Integer"])
                                                                                (refinements
                                                                                 [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                          (spec :Other :concrete)
                                                                          (spec :Spec :concrete
                                                                                (variables [:p "Integer"]
                                                                                           [:n "Integer"]
                                                                                           [:o "Integer" :optional])
                                                                                (constraints [:pc [:halite "(> p 0)"]]
                                                                                             [:pn [:halite "(< n 0)"]]))))
                               :instance {:$type :my/Other$v1}
                               :expr-str "(if-value-let [x (when (> 1 2) 19)] (inc x) 0)"
                               :expr-str-j :auto
                               :result :auto}]
                   :throws ['h-err/binding-target-must-be-bare-symbol
                            'l-err/binding-expression-not-optional]
                   :see-also ['if-value 'when-value-let]}
    'inc {:sigs [["integer" "integer"]]
          :sigs-j [["integer '+' '1'" "integer"]]
          :tags #{:integer-op :integer-out}
          :basic-ref ['integer]
          :doc "Increment a numeric value."
          :examples [{:expr-str "(inc 10)"
                      :result :auto}
                     {:expr-str "(inc 0)"
                      :result :auto}]
          :throws ['h-err/overflow]
          :see-also ['dec]}
    'intersection {:sigs [["set set {set}" "set"]]
                   :sigs-j [["set '.' 'intersection' '(' set {',' set} ')'" "set"]]
                   :tags #{:set-op :set-out}
                   :basic-ref ['set]
                   :doc "Compute the set intersection of the sets."
                   :comment "This produces a set which only contains values that appear in each of the arguments."
                   :examples [{:expr-str "(intersection #{1 2 3} #{2 3 4})"
                               :expr-str-j :auto
                               :result :auto}
                              {:expr-str "(intersection #{1 2 3} #{2 3 4} #{3 4})"
                               :expr-str-j :auto
                               :result :auto}]
                   :throws ['h-err/arguments-not-sets]
                   :see-also ['difference 'union 'subset?]}
    'let {:sigs [["'[' symbol value {symbol value} ']' any-expression" "any"]]
          :sigs-j [["'{' symbol '=' value ';' {symbol '=' value ';'} any-expression '}'" "any"]]
          :tags #{:special-form}
          :basic-ref ['symbol 'any]
          :doc "Evaluate the expression argument in a nested context created by considering the first argument in a pairwise fashion and binding each symbol to the corresponding value."
          :comment "Allows names to be given to values so that they can be referenced by the any-expression."
          :examples [{:expr-str "(let [x 1] (inc x))"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(let [x 1 y 2] (+ x y))"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(let [x 1] (let [x 2] x))"
                      :expr-str-j :auto
                      :result :auto
                      :doc "The values associated with symbols can be changed in nested contexts."}]
          :doc-j "Evaluate the expression argument in a nested context created by binding each symbol to the corresponding value."
          :throws ['h-err/let-bindings-odd-count
                   'h-err/let-needs-bare-symbol
                   'h-err/cannot-bind-reserved-word
                   'l-err/cannot-bind-unset
                   'l-err/cannot-bind-nothing
                   'l-err/binding-target-invalid-symbol
                   'l-err/let-bindings-empty
                   'l-err/disallowed-unset-variable]}
    'map {:sigs [["'[' symbol:element set ']' value-expression" "set"]
                 ["'[' symbol:element vector ']' value-expression" "vector"]]
          :sigs-j [["'map' '(' symbol:element 'in' set ')' value-expression" "set"]
                   ["'map' '(' symbol:element 'in' vector ')' value-expression" "vector"]]
          :tags #{:set-op :vector-op :set-out :vector-out :special-form}
          :basic-ref ['symbol 'value 'set 'vector]
          :doc "Produce a new collection from a collection by evaluating the expression with the symbol bound to each element of the original collection, one-by-one. The results of evaluating the expression will be in the resulting collection. When operating on a vector, the order of the output vector will correspond to the order of the items in the original vector."
          :examples [{:expr-str "(map [x [10 11 12]] (inc x))"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(map [x #{10 12}] (* x 2))"
                      :expr-str-j :auto
                      :result :auto}]
          :throws ['h-err/comprehend-binding-wrong-count
                   'h-err/comprehend-collection-invalid-type
                   'l-err/binding-target-invalid-symbol
                   'h-err/binding-target-must-be-bare-symbol
                   'h-err/must-produce-value]
          :see-also ['reduce 'filter]}
    'mod {:sigs [["integer integer" "integer"]]
          :sigs-j [["integer '%' integer" "integer"]]
          :tags #{:integer-op :integer-out}
          :basic-ref ['integer]
          :doc "Computes the mathematical modulus of two numbers. Use care if one of the arguments is negative."
          :examples [{:expr-str "(mod 12 3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(mod 14 4)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(mod 1 0)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(mod 1 3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(mod -1 3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(mod 1 -3)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(mod -1 -3)"
                      :expr-str-j :auto
                      :result :auto}]
          :throws ['h-err/divide-by-zero]}
    'not {:sigs [["boolean" "boolean"]]
          :sigs-j [["'!' boolean" "boolean"]]
          :tags #{:boolean-op :boolean-out}
          :basic-ref ['boolean]
          :doc "Performs logical negation of the argument."
          :examples [{:expr-str "(not true)"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(not false)"
                      :expr-str-j :auto
                      :result :auto}]
          :see-also ['=> 'and 'or]}
    'not= {:sigs [["value value {value}" "boolean"]]
           :sigs-j [["'notEqualTo' '(' value ',' value {',' value} ')'" "boolean"]
                    ["value '!=' value" "boolean"]]
           :tags #{:integer-op :fixed-decimal-op :set-op :vector-op :instance-op :boolean-op :boolean-out}
           :basic-ref ['value 'boolean]
           :doc "Produces a false value if all of the values are equal to each other. Otherwise produces a true value."
           :throws ['l-err/result-always-known]
           :examples [{;; TODO: include jadeite example of notEqualTo
                       :expr-str "(not= 2 3)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= #d \"2.2\" #d \"2.2\")"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= 2 2)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= 1 1 2)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= 1 1 1)"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= \"hi\" \"bye\")"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= [1 2 3] [1 2 3 4])"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= [1 2 3] #{1 2 3})"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= #{3 1 2} #{1 2 3})"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= [#{1 2} #{3}] [#{1 2} #{3}])"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(not= [#{1 2} #{3}] [#{1 2} #{4}])"
                       :expr-str-j :auto
                       :result :auto}
                      {:workspace-f (make-workspace-fn (workspace :my
                                                                  {:my/Spec []
                                                                   :my/Result []}
                                                                  (spec :Result :concrete
                                                                        (variables [:x "Boolean"])
                                                                        (refinements
                                                                         [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                  (spec :Other :concrete)
                                                                  (spec :Spec :concrete
                                                                        (variables [:x "Integer"]
                                                                                   [:y "Integer"]))))
                       :instance {:$type :my/Other$v1}
                       :expr-str "(not= {:$type :my/Spec$v1 :x 1 :y -1} {:$type :my/Spec$v1 :x 1 :y 0})"
                       :expr-str-j :auto
                       :result :auto}
                      {}]
           :see-also ['= 'not=]}
    'or {:sigs [["boolean boolean {boolean}" "boolean"]]
         :sigs-j [["boolean '||' boolean" "boolean"]]
         :tags #{:boolean-op :boolean-out}
         :basic-ref ['boolean]
         :doc "Perform a logical 'or' operation on the input values."
         :comment "The operation does not short-circuit. Even if the first argument evaluates to true the other arguments are still evaluated."
         :examples [{:expr-str "(or true false)"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(or false false)"
                     :expr-str-j :auto
                     :result :auto}
                    {:expr-str "(or (> 1 2) (> 2 3) (> 4 3))"
                     :expr-str-j :auto
                     :result :auto}]
         :see-also ['=> 'and 'any? 'not]}
    'range {:sigs [["[integer:start] integer:end [integer:increment]" "vector"]]
            :sigs-j [["'range' '(' [integer:start ','] integer:end [',' integer:increment] ')'" "vector"]]
            :basic-ref ['integer 'vector]
            :doc "Produce a vector that contains integers in order starting at either the start value or 0 if no start is provided. The final element of the vector will be no more than one less than the end value. If an increment is provided then only every increment integer will be included in the result."
            :examples [{:expr-str "(range 3)"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(range 10 12)"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(range 10 21 5)"
                        :expr-str-j :auto
                        :result :auto}]
            :throws ['h-err/size-exceeded]
            :tags #{:vector-out}}
    'reduce {:sigs [["'[' symbol:accumulator value:accumulator-init ']' '[' symbol:element vector ']' any-expression" "any"]]
             :sigs-j [["'reduce' '(' symbol:accumulator '=' value:accumulator-init ';' symbol:element 'in' vector ')' any-expression" "any"]]
             :tags #{:vector-op :special-form}
             :basic-ref ['symbol 'vector 'any]
             :doc "Evalue the expression repeatedly for each element in the vector. The accumulator value will have a value of accumulator-init on the first evaluation of the expression. Subsequent evaluations of the expression will chain the prior result in as the value of the accumulator. The result of the final evaluation of the expression will be produced as the result of the reduce operation. The elements are processed in order."
             :examples [{:expr-str "(reduce [a 10] [x [1 2 3]] (+ a x))"
                         :expr-str-j :auto
                         :result :auto}]
             :throws ['h-err/binding-target-must-be-bare-symbol
                      'h-err/element-binding-target-must-be-bare-symbol
                      'h-err/element-accumulator-same-symbol
                      'h-err/accumulator-target-must-be-bare-symbol
                      'h-err/reduce-not-vector]
             :see-also ['map 'filter]
             :notes ["'normally' a reduce will produce a value, but the body could produce a 'maybe' value or even always produce 'unset', in which case the reduce may not produce a value"]}
    'refine-to {:sigs [["instance keyword:spec-id" "instance"]]
                :sigs-j [["instance '.' 'refineTo' '(' symbol:spec-id ')'" "instance"]]
                :tags #{:instance-op :instance-out :spec-id-op}
                :basic-ref ['instance 'keyword]
                :basic-ref-j ['instance 'symbol]
                :doc "Attempt to refine the given instance into an instance of type, spec-id."
                :throws ['h-err/no-refinement-path
                         'h-err/resource-spec-not-found
                         'h-err/refinement-error
                         'h-err/invalid-refinement-expression]
                :examples [{:spec-map {:my/Spec$v1 {:spec-vars {:p "Integer"
                                                                :n "Integer"}
                                                    :constraints []
                                                    :refines-to {:an/Other$v1 {:name "r"
                                                                               :expr '{:$type :an/Other$v1
                                                                                       :x (inc p)
                                                                                       :y (dec n)}}}}
                                       :an/Other$v1 {:spec-vars {:x "Integer"
                                                                 :y "Integer"}
                                                     :constraints []
                                                     :refines-to {}}}
                            :expr-str "(refine-to {:$type :my/Spec$v1, :p 1, :n -1} :an/Other$v1)"
                            :expr-str-j :auto
                            :result :auto
                            :doc "Assuming a spec has a refinement defined to another."}
                           {:workspace-f (make-workspace-fn (workspace :my
                                                                       {:my/Spec []
                                                                        :my/Result []
                                                                        :my/Other []}
                                                                       (spec :Result :concrete
                                                                             (variables [:x :my/Other$v1])
                                                                             (refinements
                                                                              [:r :from :my/Third$v1 [:halite "placeholder"]]))
                                                                       (spec :Third :concrete)
                                                                       (spec :Other :concrete
                                                                             (variables [:x "Integer"]
                                                                                        [:y "Integer"]))
                                                                       (spec :Spec :concrete
                                                                             (variables [:p "Integer"]
                                                                                        [:n "Integer"]))))
                            :instance {:$type :my/Third$v1}
                            :expr-str "(refine-to {:$type :my/Spec$v1, :p 1, :n -1} :my/Other$v1)"
                            :expr-str-j "{$type: my/Spec$v1, n: -1, p: 1}.refineTo( my/Other$v1 )"
                            :result :auto
                            :doc "Assuming a spec does note have a refinement defined to another."}]
                :see-also ['refines-to?]}
    'refines-to? {:sigs [["instance keyword:spec-id" "boolean"]]
                  :sigs-j [["instance '.' 'refinesTo?' '(' symbol:spec-id ')'" "boolean"]]
                  :tags #{:instance-op :boolean-out :spec-id-op}
                  :basic-ref ['instance 'keyword 'boolean]
                  :basic-ref-j ['instance 'symbol 'boolean]
                  :doc "Determine whether it is possible to refine the given instance into an instance of type, spec-id."
                  :see-also ['refine-to]
                  :examples [{:workspace-f (make-workspace-fn (workspace :my
                                                                         {:my/Spec []
                                                                          :my/Result []
                                                                          :my/Other []}
                                                                         (spec :Result :concrete
                                                                               (variables [:x "Boolean" :optional])
                                                                               (refinements
                                                                                [:r :from :my/Third$v1 [:halite "placeholder"]]))
                                                                         (spec :Third :concrete)
                                                                         (spec :Other :concrete
                                                                               (variables [:x "Integer"]
                                                                                          [:y "Integer"]))
                                                                         (spec :Spec :concrete
                                                                               (variables [:p "Integer"]
                                                                                          [:n "Integer"])
                                                                               (refinements
                                                                                [:r :to :my/Other$v1 [:halite "{:$type :my/Other$v1 :x (inc p) :y (inc n)}"]]))))
                              :instance {:$type :my/Third$v1}
                              :expr-str "(refines-to? {:$type :my/Spec$v1, :p 1, :n -1} :my/Other$v1)"
                              :expr-str-j "{$type: my/Spec$v1, n: -1, p: 1}.refinesTo?( my/Other$v1 )"
                              :result :auto
                              :doc "Assuming a spec has a refinement defined to another."}
                             {:workspace-f (make-workspace-fn (workspace :my
                                                                         {:my/Spec []
                                                                          :my/Result []
                                                                          :my/Other []}
                                                                         (spec :Result :concrete
                                                                               (variables [:x "Boolean" :optional])
                                                                               (refinements
                                                                                [:r :from :my/Third$v1 [:halite "placeholder"]]))
                                                                         (spec :Third :concrete)
                                                                         (spec :Other :concrete
                                                                               (variables [:x "Integer"]
                                                                                          [:y "Integer"]))
                                                                         (spec :Spec :concrete
                                                                               (variables [:p "Integer"]
                                                                                          [:n "Integer"]))))
                              :instance {:$type :my/Third$v1}
                              :expr-str "(refines-to? {:$type :my/Spec$v1, :p 1, :n -1} :my/Other$v1)"
                              :expr-str-j "{$type: my/Spec$v1, n: -1, p: 1}.refinesTo?( my/Other$v1 )"
                              :result :auto
                              :doc "Assuming a spec does note have a refinement defined to another."}]
                  :throws ['h-err/resource-spec-not-found
                           'h-err/refinement-error
                           'h-err/invalid-refinement-expression]}
    'rescale {:sigs [["fixed-decimal integer:new-scale" "(fixed-decimal | integer)"]]
              :sigs-j [["'rescale' '(' fixed-decimal ',' integer ')'" "(fixed-decimal | integer)"]]
              :tags #{:integer-out :fixed-decimal-op :fixed-decimal-out}
              :basic-ref ['fixed-decimal 'integer]
              :doc "Produce a number by adjusting the scale of the fixed-decimal to the new-scale. If the scale is being reduced, the original number is truncated. If the scale is being increased, then the original number is padded with zeroes in the decimal places. If the new-scale is zero, then the result is an integer."
              :comment "Arithmetic on numeric values never produce results in different number spaces. This operation provides an explicit way to convert a fixed-decimal value into a value with the scale of a different number space. This includes the ability to convert a fixed-decimal value into an integer."
              :notes ["if the new scale is 0, then an integer is produced"
                      "scale cannot be negative"
                      "scale must be between 0 and 18 (inclusive)"]
              :examples [{:expr-str "(rescale #d \"1.23\" 1)"
                          :expr-str-j :auto
                          :result :auto}
                         {:expr-str "(rescale #d \"1.23\" 2)"
                          :expr-str-j :auto
                          :result :auto}
                         {:expr-str "(rescale #d \"1.23\" 3)"
                          :expr-str-j :auto
                          :result :auto}
                         {:expr-str "(rescale #d \"1.23\" 0)"
                          :expr-str-j :auto
                          :result :auto}]
              :see-also ['*]}
    'rest {:sigs [["vector" "vector"]]
           :sigs-j [["vector '.' 'rest()'" "vector"]]
           :basic-ref ['vector]
           :doc "Produce a new vector which contains the same element of the argument, in the same order, except the first element is removed. If there are no elements in the argument, then an empty vector is produced."
           :examples [{:expr-str "(rest [1 2 3])"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(rest [1 2])"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(rest [1])"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(rest [])"
                       :expr-str-j :auto
                       :result :auto}]
           :throws ['h-err/argument-not-vector]
           :tags #{:vector-op :vector-out}}
    'sort {:sigs [["(set | vector)" "vector"]]
           :sigs-j [["(set | vector) '.' 'sort()'" "vector"]]
           :tags #{:set-op :vector-op :vector-out}
           :basic-ref ['vector 'set]
           :doc "Produce a new vector by sorting all of the items in the argument. Only collections of numeric values may be sorted."
           :examples [{:expr-str "(sort [2 1 3])"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(sort [#d \"3.3\" #d \"1.1\" #d \"2.2\"])"
                       :expr-str-j :auto
                       :result :auto}]
           :see-also ['sort-by]}
    'sort-by {:sigs [["'[' symbol:element (set | vector) ']' (integer-expression | fixed-decimal-expression)" "vector"]]
              :sigs-j [["'sortBy' '(' symbol:element 'in' (set | vector) ')' (integer-expression | fixed-decimal-expression)" "vector"]]
              :tags #{:set-op :vector-op :vector-out :special-form}
              :basic-ref ['vector 'set 'integer 'symbol]
              :doc "Produce a new vector by sorting all of the items in the input collection according to the values produced by applying the expression to each element. The expression must produce a unique, sortable value for each element."
              :throws ['h-err/not-sortable-body
                       'h-err/sort-value-collision
                       'h-err/binding-target-must-be-bare-symbol
                       'l-err/binding-target-invalid-symbol]
              :examples [{:expr-str "(sort-by [x [[10 20] [30] [1 2 3]]] (first x))"
                          :expr-str-j :auto
                          :result :auto}]
              :see-also ['sort]}
    'str {:sigs [["string string {string}" "string"]]
          :sigs-j [["'str' '(' string ',' string {',' string} ')'" "string"]]
          :basic-ref ['string]
          :doc "Combine all of the input strings together in sequence to produce a new string."
          :examples [{:expr-str "(str \"a\" \"b\")"
                      :expr-str-j :auto
                      :result :auto}
                     {:expr-str "(str \"a\" \"\" \"c\")"
                      :expr-str-j :auto
                      :result :auto}]
          :tags #{:string-op}
          :throws ['h-err/size-exceeded]}
    'subset? {:sigs [["set set" "boolean"]]
              :sigs-j [["set '.' 'subset?' '(' set ')'" "boolean"]]
              :tags #{:set-op :boolean-out}
              :basic-ref ['boolean 'set]
              :doc "Return false if there are any items in the first set which do not appear in the second set. Otherwise return true."
              :comment "According to this operation, a set is always a subset of itself and every set is a subset of the empty set. Using this operation and an equality check in combination allows a 'superset?' predicate to be computed."
              :examples [{:expr-str "(subset? #{1 2} #{1 2 3 4})"
                          :expr-str-j :auto
                          :result :auto}
                         {:expr-str "(subset? #{1 5} #{1 2})"
                          :expr-str-j :auto
                          :result :auto}
                         {:expr-str "(subset? #{1 2} #{1 2})"
                          :expr-str-j :auto
                          :result :auto}]
              :see-also ['difference 'intersection 'union]}
    'union {:sigs [["set set {set}" "set"]]
            :sigs-j [["set '.' 'union' '(' set {',' set} ')'" "set"]]
            :tags #{:set-op :set-out}
            :basic-ref ['set]
            :examples [{:expr-str "(union #{1} #{2 3})"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(union #{1} #{2 3} #{4})"
                        :expr-str-j :auto
                        :result :auto}
                       {:expr-str "(union #{1} #{})"
                        :expr-str-j :auto
                        :result :auto}]
            :doc "Compute the union of all the sets."
            :comment "This produces a set which contains all of the values that appear in any of the arguments."
            :throws ['h-err/arguments-not-sets]
            :see-also ['difference 'intersection 'subset?]}
    'valid {:sigs [["instance-expression" "any"]]
            :sigs-j [["'valid' instance-expression" "any"]]
            :tags #{:instance-op :optional-out :special-form}
            :basic-ref ['instance 'any]
            :doc "Evaluate the instance-expression and produce the result. If a constraint violation occurs while evaluating the expression then produce an 'unset' value."
            :comment "This operation can be thought of as producing an instance if it is valid. This considers not just the constraints on the immediate instance, but also the constraints implied by refinements defined on the specification."

            :examples [{:workspace-f (make-workspace-fn (workspace :my
                                                                   {:my/Spec []
                                                                    :my/Result []}
                                                                   (spec :Result :concrete
                                                                         (variables [:x :my/Spec$v1, :optional])
                                                                         (refinements
                                                                          [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                   (spec :Other :concrete)
                                                                   (spec :Spec :concrete
                                                                         (variables [:p "Integer"]
                                                                                    [:n "Integer"]
                                                                                    [:o "Integer" :optional])
                                                                         (constraints [:pc [:halite "(> p 0)"]]
                                                                                      [:pn [:halite "(< n 0)"]]))))
                        :instance {:$type :my/Other$v1}
                        :expr-str "(valid {:$type :my/Spec$v1, :p 1, :n -1})"
                        :expr-str-j "valid {$type: my/Spec$v1, p: 1, n: -1}"
                        :result :auto
                        :doc "When the spec has constraints that the field, p, must be positive and the field, n, must be negative."}
                       {:workspace-f (make-workspace-fn (workspace :my
                                                                   {:my/Spec []
                                                                    :my/Result []}
                                                                   (spec :Result :concrete
                                                                         (variables [:x :my/Spec$v1, :optional])
                                                                         (refinements
                                                                          [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                   (spec :Other :concrete)
                                                                   (spec :Spec :concrete
                                                                         (variables [:p "Integer"]
                                                                                    [:n "Integer"]
                                                                                    [:o "Integer" :optional])
                                                                         (constraints [:pc [:halite "(> p 0)"]]
                                                                                      [:pn [:halite "(< n 0)"]]))))
                        :instance {:$type :my/Other$v1}
                        :expr-str "(valid {:$type :my/Spec$v1, :p 1, :n 1})"
                        :expr-str-j "valid {$type: my/Spec$v1, p: 1, n: 1}"
                        :result :auto
                        :doc "When the spec has constraints that the field, p, must be positive and the field, n, must be negative."}]
            :see-also ['valid?]}
    'valid? {:sigs [["instance-expression" "boolean"]]
             :sigs-j [["'valid?' instance-expression" "boolean"]]
             :basic-ref ['instance 'boolean]
             :doc "Evaluate the instance expression and produce false if a constraint violation occurs during the evaluation. Otherwise, produce true."
             :comment "Similar to 'valid', but insted of possibly producing an instance, it produces a boolean indicating whether the instance was valid. This can be thought of as invoking a specification as a single predicate on a candidate instance value."
             :examples [{:workspace-f (make-workspace-fn (workspace :my
                                                                    {:my/Spec []
                                                                     :my/Result []}
                                                                    (spec :Result :concrete
                                                                          (variables [:x "Boolean" :optional])
                                                                          (refinements
                                                                           [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                    (spec :Other :concrete)
                                                                    (spec :Spec :concrete
                                                                          (variables [:p "Integer"]
                                                                                     [:n "Integer"]
                                                                                     [:o "Integer" :optional])
                                                                          (constraints [:pc [:halite "(> p 0)"]]
                                                                                       [:pn [:halite "(< n 0)"]]))))
                         :instance {:$type :my/Other$v1}
                         :expr-str "(valid? {:$type :my/Spec$v1, :p 1, :n -1})"
                         :expr-str-j "valid? {$type: my/Spec$v1, p: 1, n: -1}"
                         :result :auto
                         :doc "When the spec has constraints that the field, p, must be positive and the field, n, must be negative."}
                        {:workspace-f (make-workspace-fn (workspace :my
                                                                    {:my/Spec []
                                                                     :my/Result []}
                                                                    (spec :Result :concrete
                                                                          (variables [:x "Boolean" :optional])
                                                                          (refinements
                                                                           [:r :from :my/Other$v1 [:halite "placeholder"]]))
                                                                    (spec :Other :concrete)
                                                                    (spec :Spec :concrete
                                                                          (variables [:p "Integer"]
                                                                                     [:n "Integer"]
                                                                                     [:o "Integer" :optional])
                                                                          (constraints [:pc [:halite "(> p 0)"]]
                                                                                       [:pn [:halite "(< n 0)"]]))))
                         :instance {:$type :my/Other$v1}
                         :expr-str "(valid? {:$type :my/Spec$v1, :p 1, :n 0})"
                         :expr-str-j "valid? {$type: my/Spec$v1, p: 1, n: 0}"
                         :result :auto
                         :doc "When the spec has constraints that the field, p, must be positive and the field, n, must be negative."}]
             :tags #{:instance-op :boolean-out :special-form}
             :see-also ['valid]}
    'when {:sigs [["boolean any-expression" "any"]]
           :sigs-j [["'when' '(' boolean ')' any-expression" "any"]]
           :tags #{:boolean-op :optional-out :control-flow :special-form}
           :basic-ref ['boolean 'any]
           :doc "If the first argument is true, then evaluate the second argument, otherwise produce 'unset'."
           :examples [{:expr-str "(when (> 2 1) \"bigger\")"
                       :expr-str-j :auto
                       :result :auto}
                      {:expr-str "(when (< 2 1) \"bigger\")"
                       :expr-str-j :auto
                       :result :auto}]
           :comment "A primary use of this operator is in instance expression to optionally provide a value for a an optional field."}
    'when-value {:sigs [["symbol any-expression:binding" "any"]]
                 :sigs-j [["'whenValue' '(' symbol ')' any-expression" "any"]]
                 :tags #{:optional-op :optional-out :control-flow :special-form}
                 :basic-ref ['symbol 'any]
                 :doc "Consider the value bound to the symbol. If it is a 'value', then evaluate the second argument. If instead it is 'unset' then produce unset."
                 :examples [{:workspace-f (make-workspace-fn (workspace :my
                                                                        {:my/Spec []
                                                                         :my/Result []}
                                                                        (spec :Result :concrete
                                                                              (variables [:x "Integer" :optional])
                                                                              (refinements
                                                                               [:r :from :my/Spec$v1 [:halite "placeholder"]]))
                                                                        (spec :Spec :concrete
                                                                              (variables [:x "Integer" :optional]))))
                             :instance {:$type :my/Spec$v1, :x 1}
                             :expr-str "(when-value x (+ x 2))"
                             :expr-str-j "whenValue(x) {x + 2}"
                             :doc "In the context of an instance with an optional field, x, when the field is set to the value of '1'."
                             :result :auto}
                            {:workspace-f (make-workspace-fn (workspace :my
                                                                        {:my/Spec []
                                                                         :my/Result []}
                                                                        (spec :Result :concrete
                                                                              (variables [:x "Integer" :optional])
                                                                              (refinements
                                                                               [:r :from :my/Spec$v1 [:halite "placeholder"]]))
                                                                        (spec :Spec :concrete
                                                                              (variables [:x "Integer" :optional]))))
                             :instance {:$type :my/Spec$v1}
                             :expr-str "(when-value x (+ x 2))"
                             :expr-str-j "whenValue(x) {x + 2}"
                             :doc "In the context of an instance with an optional field, x, when the field is unset."
                             :result :auto}]
                 :see-also ['if-value 'when 'when-value-let]}
    'when-value-let {:sigs [["'[' symbol any:binding']' any-expression" "any"]]
                     :sigs-j [["'whenValueLet' '(' symbol '=' any:binding ')' any-expression" "any"]]
                     :tags #{:optional-op :optional-out :control-flow :special-form}
                     :basic-ref ['symbol 'any]
                     :doc "If the binding value is a 'value' then evaluate the second argument with the symbol bound to binding. If instead, the binding value is 'unset', then produce 'unset'"
                     :examples [{:workspace-f (make-workspace-fn (workspace :my
                                                                            {:my/Spec []
                                                                             :my/Result []}
                                                                            (spec :Result :concrete
                                                                                  (variables [:x "Integer" :optional])
                                                                                  (refinements
                                                                                   [:r :from :my/Spec$v1 [:halite "placeholder"]]))
                                                                            (spec :Spec :concrete
                                                                                  (variables [:y "Integer" :optional]))))
                                 :instance {:$type :my/Spec$v1, :y 1}
                                 :expr-str "(when-value-let [x (when-value y (+ y 2))] (inc x))"
                                 :expr-str-j "(whenValueLet ( x = (whenValue(y) {(y + 2)}) ) {(x + 1)})"
                                 :result :auto
                                 :doc "In the context of an instance with an optional field, y, when the field is set to the value of '1'."}
                                {:workspace-f (make-workspace-fn (workspace :my
                                                                            {:my/Spec []
                                                                             :my/Result []}
                                                                            (spec :Result :concrete
                                                                                  (variables [:x "Integer" :optional])
                                                                                  (refinements
                                                                                   [:r :from :my/Spec$v1 [:halite "placeholder"]]))
                                                                            (spec :Spec :concrete
                                                                                  (variables [:y "Integer" :optional]))))
                                 :instance {:$type :my/Spec$v1}
                                 :expr-str "(when-value-let [x (when-value y (+ y 2))] (inc x))"
                                 :expr-str-j "(whenValueLet ( x = (whenValue(y) {(y + 2)}) ) {(x + 1)})"
                                 :doc "In the context of an instance with an optional field, y, when the field is unset."
                                 :result :auto}]
                     :see-also ['if-value-let 'when 'when-value]}}))

(def jadeite-ommitted-ops #{'dec 'inc})

(def jadeite-operator-map {'= ['equalTo '==]
                           'sort-by ['sortBy]
                           'and ['&&]
                           'div ['/]
                           'get ['ACCESSOR]
                           'get-in ['ACCESSOR-CHAIN]
                           'when-value ['whenValue]
                           'when-value-let ['whenValueLet]
                           'if-value ['ifValue]
                           'if-value-let ['ifValueLet]
                           'mod ['%]
                           'not ['!]
                           'not= ['notEqualTo '!=]
                           'or ['||]
                           'refine-to ['refineTo]
                           'refines-to? ['refinesTo?]})

(def thrown-by-map (->> (-> op-maps
                            (update-vals :throws))
                        (remove (comp nil? second))
                        (mapcat (fn [[k vs]] (map (fn [v] {v #{k}}) vs)))
                        (apply merge-with into)))

(def thrown-by-basic-map (->> basic-bnf
                              (partition 2)
                              (map (fn [[k v]]
                                     [k (:throws v)]))
                              (remove (comp nil? second))
                              (mapcat (fn [[k vs]] (map (fn [v] {v #{k}}) vs)))
                              (apply merge-with into)))

(defn translate-op-name-to-jadeite [op-name]
  (if-let [op-names-j (get jadeite-operator-map op-name)]
    (first op-names-j)
    op-name))

(defn translate-op-name-to-jadeite-plural [op-name]
  (if-let [op-names-j (get jadeite-operator-map op-name)]
    op-names-j
    [op-name]))

(def err-maps
  (apply hash-map
         (mapcat (fn [[err-id err]]
                   [err-id (let [err (if-let [thrown-by (thrown-by-map err-id)]
                                       (assoc err
                                              :thrown-by (vec thrown-by)
                                              :thrown-by-j (vec (mapcat translate-op-name-to-jadeite-plural
                                                                        (remove jadeite-ommitted-ops thrown-by))))
                                       err)
                                 err (if-let [thrown-by (thrown-by-basic-map err-id)]
                                       (assoc err
                                              :thrown-by-basic (vec thrown-by))
                                       err)]
                             err)])
                 (filter (fn [[err-id err]]
                           (#{'jibe.halite.l-err 'jibe.h-err} (:ns-name err)))
                         (merge-with merge
                                     @format-errors/error-atom
                                     {'h-err/abs-failure {:doc "The way the number space is divided the value of zero comes out of the positive number space. This means there is one more negative number than there are positive numbers. So there is one negative number whose absolute value cannot be represented. That negative number is the most negative value."}
                                      'h-err/accumulator-target-must-be-bare-symbol {:doc "In 'reduce', it is necesary to define a symbol to reference the accumulated value of the reduction. This symbol must not include a namespace."
                                                                                     :see-also ['h-err/element-binding-target-must-be-bare-symbol]}
                                      'h-err/arg-type-mismatch {:doc "A relatively generic exception that indicates the operator being invoked cannot operate on the type of value provided."}
                                      'h-err/not-both-vectors {:doc "When the first argument to 'concat' is a vector, the second must also be a vector. A vector can be concated onto a set, but a set cannot be concated onto a vector."}
                                      'h-err/argument-empty {:doc "The 'first' operation cannot be invoked on an empty collection."}
                                      'h-err/argument-not-set-or-vector {:doc "The operation must be invoked a collection."}
                                      'h-err/argument-not-vector {:doc "The operation can only be invoked on a vector."}
                                      'h-err/arguments-not-sets {:doc "The operation can only be invoked on set arguments."}
                                      'h-err/binding-target-must-be-bare-symbol {:doc "In binding forms, the first value of each pair must be a symbol without a namespace. This symbol is an identifier that will be bound to the value of the second item in the pair."}
                                      'h-err/cannot-bind-reserved-word {:doc "There are a small number of symbols that are reserved for system use and cannot be used by users in bindings."}
                                      'h-err/cannot-conj-unset {:doc "Only actual values can be added into collections. Specifically 'unset' cannot be added into a collection."}
                                      'h-err/comprehend-binding-wrong-count {:doc "Collection comprehensions require a single binding that defines the symbol to be bound to the elements of the collection."}
                                      'h-err/comprehend-collection-invalid-type {:doc "Collection comprehensions can only be applied to collections, i.e. vectors or sets."}
                                      'h-err/divide-by-zero {:doc "Division by zero, whether directly or indirectly via modulus cannot be performed."}
                                      'h-err/element-accumulator-same-symbol {:doc "The 'reduce' operation requires distinct symbols for referring to the accumulator and the collection element."}
                                      'h-err/element-binding-target-must-be-bare-symbol {:doc "In 'reduce', it is necesary to define a symbol without a namepsace which is used to hold each element of the collection."
                                                                                         :see-also ['h-err/accumulator-target-must-be-bare-symbol]}
                                      'h-err/get-in-path-must-be-vector-literal {:doc "The path to navigate in 'get-in' must be a literal, i.e. it cannot be an expression to compute a vector."}
                                      'h-err/if-value-must-be-bare-symbol {:doc "The 'if-value' operator can only be applied to a bare symbol that is already bound to an optional value."}
                                      'h-err/index-out-of-bounds {:doc "The index falls outside of the bounds of the vector. A way to avoid this is to first test the length of the vector."}
                                      'h-err/invalid-exponent {:doc "The exponent cannot be negative."}
                                      'h-err/invalid-expression {:doc "The expression itself was not recognized as a value that could be evaluated."}
                                      'h-err/field-value-of-wrong-type {:doc "The value did not match the type of the spec field."}
                                      'h-err/value-of-wrong-type {:doc "The value did not match the expected type for this symbol in the context."}
                                      'h-err/invalid-instance {:doc "An attempt was made to create an instance that violated a spec constraint."}
                                      'h-err/invalid-instance-index {:doc "An attempt was made to a read a value from an instance, but a field name was not provided as an index, instead a value such as an integer was provided."}
                                      'h-err/invalid-keyword-char {:doc "Only certain characters, in certain sequences are allowed to appear in keywords."}
                                      'h-err/invalid-keyword-length {:doc "The length of keywords is limited. The supplied keyword exceeded the limit."}
                                      'h-err/invalid-lookup-target {:doc "An attempt was made to retrieve a field from an instance but the value was not known to be an instance of a specific spec. For example, the value may have been missing, as in the case of an optional field. Or perhaps the instance was a result of an expression and the result could have been an instance of many alternative specs."}
                                      'h-err/invalid-refinement-expression {:doc "A refinement expression must produce an instance whose :$type matches the type that is declared on the refinement."
                                                                            :doc-j "A refinement expression must produce an instance whose $type matches the type that is declared on the refinement."}
                                      'h-err/invalid-symbol-char {:doc "Only certain characters, in certain sequences are allowed to appear in symbols."}
                                      'h-err/invalid-symbol-length {:doc "The length of symbols is limited. The supplied symbol exceeded the limit."}
                                      'h-err/invalid-type-value {:doc "The value of the :$type field in an instance must be a keyword that includes a '/' separator."
                                                                 :doc-j "The value of the $type field in an instance must be a symbol that includes a '/' separator."}
                                      'h-err/invalid-collection-type {:doc "This indicates that a collection value was provided, but the collection is not of a type that is supported."}
                                      'h-err/invalid-value {:doc "A value was supplied, but the type of the value is not recognized."}
                                      'h-err/invalid-vector-index {:doc "An index was supplied to lookup a value in a vector, but the index was not an integer."}
                                      'h-err/let-bindings-odd-count {:doc "A 'let' expression included an odd number of elements in the binding vector. This is invalid as the bindings are to be a sequence of pairs."}
                                      'h-err/let-needs-bare-symbol {:doc "In a 'let' expression, the first item in each pair of items must be a symbol."}
                                      'h-err/limit-exceeded {:doc "There are various, context specific, limits that are enforced. e.g. limits on how deeply expressions may be nested. One of these limits was violated. See the exception data for more details."}
                                      'h-err/literal-must-evaluate-to-value {:doc "All of the expressions that appear as elements in a collection literal, must be guaranteed to evaluate to values, i.e. they must never evaluate to 'unset'."}
                                      'h-err/missing-required-vars {:doc "An attempt was made to construct an instance of a spec, without all of its mandatory fields being assigned values."}
                                      'h-err/missing-type-field {:doc "An attempt was made to construct an instance without providing a value for the :$type field."
                                                                 :doc-j "An atftempt was made to construct an instance without providing a value for the $type field."}
                                      'h-err/must-produce-value {:doc "When using 'map', the expression being evaluated on each element, must produce an actual value, i.e. it must be never produce 'unset'."}
                                      'h-err/no-abstract {:doc "An attempt was made to construct a concrete instance with a field whose value is an instance of an abstract spec. Any instances used to compose a concrete instance, must themselves be concrete."}
                                      'h-err/no-refinement-path {:doc "There was no refinement path found to convert a specific instance to a target spec type. There may have been a conditional refinement that did not match the instance, or perhaps there is no refinement path at all."}
                                      'h-err/no-matching-signature {:doc "An attempt was made to invoke an operation, but either the number of arguments or the types of the arguments was not valid."}
                                      'h-err/not-boolean-body {:doc "Either an 'any?', 'every?', or 'filter' call was attempted but the expression to evaluate for each element in the collection did not produce a boolean value."}
                                      'h-err/not-boolean-constraint {:doc "All constraint expressions on specs must produce boolean values. The constraints are predicates which evaluate to true or false to indicate whether the constraint has been met by the instance state."}
                                      'h-err/not-sortable-body {:doc "When using 'sort-by', the expression used for sorting must produce a value that can be sorted."}
                                      'h-err/overflow {:doc "The mathematical operation resulted in a number that is too large to fit in the bytes alloted for the numeric type."}
                                      'h-err/reduce-not-vector {:doc "The 'reduce' operation can only be applied to vectors. Specifically, sets cannot be reduced."}
                                      'h-err/refinement-error {:doc "An unanticipated error condition was encountered while computing the refinement of an instance."}
                                      'h-err/resource-spec-not-found {:doc "The spec identifier provided did not correspond to a known spec."}
                                      'h-err/size-exceeded {:doc "There are various, context specific, limits that are enforced. e.g. limits on the lengths of strings. One of these limits was violated. See the exception data for more details."}
                                      'h-err/sort-value-collision {:doc "When sorting a collection with 'sort-by', the sort expression must produce a unique value for each element in the collection."}
                                      'h-err/spec-threw {:doc "An explicit invocation of the 'error' operation was encountered in a spec. This indicates that the spec author considers it not possible to proceed in the encountered situation. See the error string in the exception detail for details."}
                                      'h-err/symbol-undefined {:doc "An unbound symbol was referenced in an expression at evaluation time."}
                                      'h-err/symbols-not-bound {:doc "Unbound symbols are referenced in an expression at type-check time."}
                                      'h-err/syntax-error {:doc "An object appeared in an expression that is not one of the expected values for the language."}
                                      'h-err/undefined-symbol {:doc "An unbound symbol was referenced in an expression at type-check time."}
                                      'h-err/unknown-function-or-operator {:doc "The operator being invoked is not recognized as a valid operation."}
                                      'h-err/field-name-not-in-spec {:doc "The field name is not valid for the spec. The field name was provided to either define a field value in an instance or to lookup a field in an instance."}
                                      'h-err/wrong-arg-count {:doc "The number of arguments provided to the operation did not match what was expected."}
                                      'h-err/wrong-arg-count-min {:doc "The operation expected at least a certain number of arguments. This minimum was not met."}
                                      'l-err/binding-expression-not-optional {:doc "The expression being tested in an 'if-value-let' statement must optionally produce a value."}
                                      'l-err/binding-target-invalid-symbol {:doc "The symbols to be bound are not to start with a '$'."}
                                      'l-err/cannot-bind-nothing {:doc "It is not permitted to bind a symbol to 'nothing'."}
                                      'l-err/cannot-bind-unset {:doc "It is not permitted to rebind the symbol used to represent 'unset'. Instead of defining a symbol for this, consider using '$no-value'."}
                                      'l-err/disallowed-nothing {:doc "An expression was encountered that does not have a value, but it was used in a place where a value is required. Examples of expressions that do not have values are an invocation of 'error' and the binding of a symbol to an element in an empty collection."}
                                      'l-err/first-argument-not-optional {:doc "The value being tested in an 'if-value' statement must be optional."}
                                      'l-err/get-in-path-empty {:doc "A path must be provided to the 'get-in' operation."}
                                      'l-err/let-bindings-empty {:doc "The bindings form of the 'let' cannot be empty. If there is nothing to bind, then the 'let' can be omitted."}
                                      'l-err/result-always-known {:doc "The result of the equality check is always the same and can be known in advance, so a check is not needed."}
                                      'l-err/disallowed-unset-variable {:doc "It is not allowed to bind 'unset' to symbols other than the built-in '$no-value'."}})))))

(defn translate-op-maps-to-jadeite [op-maps]
  (let [op-maps (-> op-maps
                    (update-vals (fn [op]
                                   (if (:see-also op)
                                     (update-in op [:see-also] #(->> %
                                                                     (mapcat translate-op-name-to-jadeite-plural)
                                                                     sort
                                                                     vec))
                                     op))))]
    (->> op-maps
         (mapcat (fn [[k v]]
                   (->> (translate-op-name-to-jadeite-plural k)
                        (mapcat (fn [k']
                                  [k' (cond
                                        (#{'== '!=} k') (update-in v [:sigs-j] (comp vector second))
                                        (#{'equalTo 'notEqualTo} k') (update-in v [:sigs-j] (comp vector first))
                                        :default v)])))))
         (apply sorted-map))))

(def op-maps-j (translate-op-maps-to-jadeite op-maps))

(def tag-map (->> (-> op-maps
                      (update-vals :tags))
                  (remove (comp nil? second))
                  (mapcat (fn [[k vs]] (map (fn [v] {v #{k}}) vs)))
                  (apply merge-with into)))

(def tag-map-j (->> (-> op-maps-j
                        (update-vals :tags))
                    (remove (comp nil? second))
                    (mapcat (fn [[k vs]] (map (fn [v] {v #{k}}) vs)))
                    (apply merge-with into)))

(defn adjust-connector-style
  "RRDiagramToSVG provides an interface for setting on connector path strokes
  only their color, nothing else. So to adjust width, we have to apply unstable
  private knowledge."
  [svg-str]
  (let [desired-style "fill: none; stroke: #888888; stroke-width: 2px;"]
    (-> svg-str
        (string/replace #"(<style.*?>.*[.]c[{]).*?([}].*</style>)"
                        (str "$1" desired-style "$2"))
        (string/replace #"(d=\"M0 )(\d+)"
                        (fn [[_ prefix y-offset-str]]
                          (str prefix (+ -4 (Long/parseLong y-offset-str))))))))

(defn produce-diagram [out-file-name ^String rule-str]
  (let [gtrd (GrammarToRRDiagram.)
        rts (RRDiagramToSVG.)
        rule-svg (->> rule-str
                      (.convert (BNFToGrammar.))
                      .getRules
                      (into [])
                      (map #(.convert gtrd %))
                      (map #(.convert rts %))
                      first
                      adjust-connector-style)]
    (spit out-file-name rule-svg)))

(defn- rule-from-partitioned-bnf [partitioned-bnf k-f]
  (str "RULE = "
       "("
       (->> partitioned-bnf
            (map (fn [[n m]]
                   (let [bnf (k-f m)]
                     (when bnf
                       (str "("
                            "'" n ":' " "(" bnf ")"
                            ")")))))
            (remove nil?)
            (string/join " |\n"))
       ")"
       ";"))

(defn produce-basic-bnf-diagrams [all-file-name all-file-name-j basic-bnf]
  (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" all-file-name)
                   (rule-from-partitioned-bnf (partition 2 basic-bnf) :bnf))
  (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" all-file-name-j)
                   (rule-from-partitioned-bnf (partition 2 basic-bnf) (fn [bnf-map] (get bnf-map :bnf-j (:bnf bnf-map)))))

  (->> (partition 2 basic-bnf)
       (map (fn [[n {:keys [bnf]}]]
              (when bnf
                (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" n ".svg")
                                 (str "RULE = " "(" bnf ")" ";")))))
       dorun)

  (->> (partition 2 basic-bnf)
       (map (fn [[n bnf-map]]
              (let [bnf-j (get bnf-map :bnf-j (:bnf bnf-map))]
                (when bnf-j
                  (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" (translate-op-name-to-jadeite n) "-j" ".svg")
                                   (str "RULE = " "(" bnf-j ")" ";"))))))
       dorun))

#_(defn produce-basic-bnf-diagrams-for-tag [basic-bnf tag]
    (let [filtered-partitioned-bnf (->> (partition 2 basic-bnf)
                                        (filter (fn [[k v]]
                                                  (let [{:keys [tags]} v]
                                                    (when tags
                                                      (tags tag))))))]
      (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" (name tag) ".svg")
                       (rule-from-partitioned-bnf filtered-partitioned-bnf :bnf))
      (produce-diagram (str "doc/halite-bnf-diagrams/basic-syntax/" (str (name tag) "-j" ".svg"))
                       (rule-from-partitioned-bnf filtered-partitioned-bnf (fn [bnf-map] (get bnf-map :bnf-j (:bnf bnf-map)))))))

(defn safe-op-name [s]
  (get {'+ 'plus
        '- 'minus
        '% 'mod
        '&& 'and
        '|| 'or
        '! 'not
        '/ 'div
        '== 'doublequal
        '!= 'notequal} s s))

(defn produce-bnf-diagrams [op-maps op-maps-j all-filename all-filename-j]
  (let [op-keys (keys op-maps)
        rules-strs (->> op-maps
                        (mapcat (fn [[op {:keys [sigs]}]]
                                  (->> sigs
                                       (map (fn [i [args-bnf result-bnf]]
                                              {:op-name op
                                               :sig-index i
                                               :rule-str (str (string/replace op "=" "equal") i " = "
                                                              (when-not (string/starts-with? op "$") "'('")
                                                              "'" op "' " args-bnf
                                                              (when-not (string/starts-with? op "$") "')'")
                                                              " '»' " result-bnf ";")})
                                            (range))))))
        rules-strs-j (->> op-maps-j
                          (mapcat (fn [[op {:keys [sigs-j]}]]
                                    (->> sigs-j
                                         (map (fn [i [args-bnf result-bnf]]
                                                {:op-name op
                                                 :sig-index i
                                                 :rule-str (str (string/replace op "=" "equal") i " = "
                                                                (str "( " args-bnf
                                                                     " '»' " result-bnf " )"))})
                                              (range))))))
        single-rules-strs (->> op-maps
                               (map (fn [[op {:keys [sigs]}]]
                                      (str "("
                                           (when-not (string/starts-with? op "$") "'('")
                                           "'" op "' "
                                           " ( "
                                           (->> sigs
                                                (map (fn [[args-bnf result-bnf]]
                                                       (str "( " args-bnf
                                                            (when-not (string/starts-with? op "$") "')'")
                                                            " '»' " result-bnf " )")))
                                                (string/join " |\n"))
                                           " ) "

                                           ")"))))
        single-rules-strs-j (->> op-maps-j
                                 (map (fn [[op {:keys [sigs-j]}]]
                                        [op (str " ( "
                                                 (->> sigs-j
                                                      (map (fn [[args-bnf result-bnf]]
                                                             (str "( " args-bnf
                                                                  " '»' " result-bnf " )")))
                                                      (string/join " |\n"))
                                                 " ) ")]))
                                 (remove nil?))]
    (let [rule-str (str "RULE = "
                        "("
                        (->> single-rules-strs
                             sort
                             (string/join " |\n"))
                        ")"
                        ";")]
      (produce-diagram (str "doc/halite-bnf-diagrams/" all-filename) rule-str))
    (let [rule-str (str "RULE = "
                        "("
                        (->> single-rules-strs-j
                             (sort-by first)
                             (map second)
                             (string/join " |\n"))
                        ")"
                        ";")]
      (produce-diagram (str "doc/halite-bnf-diagrams/" all-filename-j) rule-str))
    (->> rules-strs
         (map (fn [{:keys [op-name sig-index ^String rule-str]}]
                (produce-diagram (str "doc/halite-bnf-diagrams/op/" (str (safe-op-name op-name) "-" sig-index ".svg")) rule-str)))
         dorun)
    (->> rules-strs-j
         (map (fn [{:keys [op-name sig-index ^String rule-str]}]
                (produce-diagram (str "doc/halite-bnf-diagrams/op/" (str (safe-op-name op-name) "-" sig-index "-j" ".svg")) rule-str)))
         dorun)))

(def safe-char-map
  (let [weird "*!$?=<>_+."
        norml "SBDQELGUAP"]
    (zipmap weird (map #(str "_" %) norml))))

(defn safe-op-anchor [s]
  (apply str (map #(safe-char-map % %) (str s))))

(defn url-encode [s]
  (java.net.URLEncoder/encode (str s)))

(defn text-width [s]
  (apply max 0 (map count (re-seq #".*" s))))

(defn text-tile-rows [texts]
  (let [chars-per-col 20
        cols-per-row 5]
    (reduce (fn [rows text]
              (let [cols (inc (quot (dec (text-width text)) chars-per-col))
                    tile {:text text, :cols cols}
                    last-row (peek rows)]
                (if (or (empty? rows)
                        (< cols-per-row (+ cols (:cols last-row))))
                  (conj rows {:cols cols :tiles [tile]})
                  (conj (pop rows) (-> last-row
                                       (update :cols + cols)
                                       (update :tiles conj tile))))))
            []
            texts)))

(defn example-text [lang e]
  (let [{:keys [spec-map doc]} e
        expr (if (= :halite lang)
               (:expr-str e)
               (or (:expr-str-j e)
                   (:expr-str e)))
        result (or (:result e)
                   (:err-result e))]
    (str (when doc
           (str ({:halite  ";-- "
                  :jadeite "### "}
                 lang)
                doc
                "\n"))
         (when spec-map
           (str ({:halite  ";-- context --;\n"
                  :jadeite "### context ###\n"}
                 lang)
                ({:halite (with-out-str (pprint/pprint spec-map))
                  :jadeite (json/encode spec-map {:pretty true})} lang)
                ({:halite  "\n;--\n\n"
                  :jadeite "\n###\n\n"}
                 lang)))
         expr
         (when result ({:halite  "\n\n;-- result --;\n"
                        :jadeite "\n\n### result ###\n"}
                       lang))
         (when result result))))

(defn tag-md-filename [lang tag]
  (str "halite-" tag "-reference" (when (= :jadeite lang) "-j") ".md"))

(def tag-def-map {:boolean-op {:label "Boolean operations"
                               :doc "Operations that operate on boolean values."
                               :basic-ref 'boolean}
                  :boolean-out {:label "Produce booleans"
                                :doc "Operations that produce boolean output values."
                                :basic-ref 'boolean}

                  :string-op {:label "String operations"
                              :doc "Operations that operate on string values."
                              :basic-ref 'string}
                  :integer-op {:label "Integer operations"
                               :doc "Operations that operate on integer values."
                               :basic-ref 'integer}
                  :integer-out {:label "Produce integer"
                                :doc "Operations that produce integer output values."
                                :basic-ref 'integer}

                  :fixed-decimal-op {:label "Fixed-decimal operations"
                                     :doc "Operations that operate on fixed-decimal values."
                                     :basic-ref 'fixed-decimal}
                  :fixed-decimal-out {:label "Produce fixed-decimals"
                                      :doc "Operations that produce fixed-decimal output values."
                                      :basic-ref 'fixed-decimal}

                  :set-op {:label "Set operations"
                           :doc "Operations that operate on sets."
                           :basic-ref 'set}
                  :set-out {:label "Produce sets"
                            :doc "Operations that produce sets."
                            :basic-ref 'set}
                  :vector-op {:label "Vector operations"
                              :doc "Operations that operate on vectors."
                              :basic-ref 'vector}
                  :vector-out {:label "Produce vectors"
                               :doc "Operations that produce vectors."
                               :basic-ref 'vector}

                  :instance-op {:label "Instance operations"
                                :doc "Operations that operate on spec instances."
                                :basic-ref 'instance}
                  :instance-out {:label "Produce instances"
                                 :doc "Operations that produce spec instances."
                                 :basic-ref 'instance}
                  :instance-field-op {:label "Instance field operations"
                                      :doc "Operations that operate on fields of spec-instances."
                                      :basic-ref 'instance}
                  :spec-id-op {:label "Spec-id operations"
                               :doc "Operations that operate on spec identifiers."
                               :basic-ref 'keyword
                               :basic-ref-j 'symbol}

                  :optional-op {:label "Optional operations"
                                :doc "Operations that operate on optional fields and optional values in general."}
                  :optional-out {:label "Optionally produce values"
                                 :doc "Operations that produce optional values."}
                  :nothing-out {:label "Produce nothing"
                                :doc "Operations that produce 'nothing'."}

                  :control-flow {:label "Control flow"
                                 :doc "Operators that control the flow of execution of the code."}
                  :special-form {:label "Special forms"
                                 :doc "Operators that do not evaluate their arguments in the 'normal' way."}})
(defn full-md [lang op-name op]
  (->> ["### "
        "<a name=\"" (safe-op-anchor op-name) "\"></a>"
        op-name "\n\n" (if (= :halite lang) (:doc op) (or (:doc-j op) (:doc op))) "\n\n"
        (when-let [d2 (:doc-2 op)] [d2 "\n\n"])
        (map-indexed
         (fn [i sig]
           ["![" (pr-str sig) "](./halite-bnf-diagrams/op/"
            (url-encode (safe-op-name op-name)) "-" i (when (= :jadeite lang) "-j") ".svg)\n\n"])
         (op ({:halite :sigs, :jadeite :sigs-j} lang)))
        (when-let [basic-refs (some-> (if (= :halite lang)
                                        (:basic-ref op)
                                        (or (:basic-ref-j op)
                                            (:basic-ref op)))
                                      sort)]
          ["#### Basic elements:\n\n"
           (string/join ", "
                        (for [basic-ref basic-refs]
                          (str "[`" basic-ref "`]"
                               "("
                               (if (= :halite lang)
                                 "halite-basic-syntax-reference.md"
                                 "jadeite-basic-syntax-reference.md")
                               "#" basic-ref
                               ")")))
           "\n\n"])
        (when-let [c (:comment op)] [c "\n\n"])
        (when-let [es (:examples op)]
          ["#### Examples:\n\n"
           "<table>"
           (for [row (text-tile-rows (map (partial example-text lang) es))]
             ["<tr>"
              (for [tile (:tiles row)]
                ["<td colspan=\"" (:cols tile) "\">\n\n"
                 "```" ({:halite "clojure", :jadeite "java"} lang) "\n"
                 (:text tile)
                 "\n```\n\n</td>"])
              "</tr>"])
           "</table>\n\n"])
        (when-let [t (:throws op)]
          ["#### Possible errors:\n\n"
           (for [msg (sort t)]
             (str "* " "[`" msg "`]("
                  (if (= :halite lang)
                    "halite-err-id-reference.md"
                    "jadeite-err-id-reference.md")
                  "#" (safe-op-anchor msg) ")" "\n"))
           "\n"])
        (when-let [alsos (:see-also op)]
          ["See also:"
           (for [a (sort (remove #(= op-name %) alsos))]
             [" [`" a "`](#" (safe-op-anchor a) ")"])
           "\n\n"])
        (when-let [tags (:tags op)]
          ["#### Tags:" "\n\n"
           (string/join ", "
                        (for [a (sort tags)]
                          (let [a (name a)]
                            (str " [" (:label (tag-def-map (keyword a))) "]("
                                 (tag-md-filename lang a)
                                 ")"))))
           "\n\n"])
        "---\n"]
       flatten (apply str)))

(def generated-msg
  "<!---
  This markdown file was generated. Do not edit.
  -->\n\n")

(defn full-intro [lang]
  ["## All Operators\n\n"
   "The syntax for all of the operators is summarized in the following \n\n"
   "![" "all operators" "](./halite-bnf-diagrams/" (if (= :halite lang) "halite" "jadeite") ".svg)\n\n"
   "## Operators\n\n"])

(defn produce-full-md []
  (->> op-maps
       sort
       (map (partial apply full-md :halite))
       (apply str generated-msg
              (apply str "# Halite operator reference (all operators)\n\n"
                     (full-intro :halite)))
       (spit "doc/halite-full-reference.md"))
  (->> op-maps-j
       sort
       (remove (fn [[k _]]
                 (jadeite-ommitted-ops k)))
       (map (partial apply full-md :jadeite))
       (apply str generated-msg
              (apply str "# Jadeite operator reference (all operators)\n\n"
                     (full-intro :jadeite)))
       (spit "doc/jadeite-full-reference.md")))

(defn basic-md [lang op-name op]
  (let [bnf (if (= :halite lang)
              (:bnf op)
              (if (contains? (set (keys op)) :bnf-j)
                (:bnf-j op)
                (or (:bnf-j op)
                    (:bnf op))))]
    (when bnf
      (->> ["### "
            "<a name=\"" (safe-op-anchor op-name) "\"></a>"
            op-name "\n\n" (if (= :halite lang) (:doc op) (or (:doc-j op) (:doc op))) "\n\n"
            (when-let [d2 (:doc-2 op)] [d2 "\n\n"])
            ["![" (pr-str bnf) "](./halite-bnf-diagrams/basic-syntax/"
             (url-encode (safe-op-name op-name)) (when (= :jadeite lang) "-j") ".svg)\n\n"]

            (let [c-1 (if (= :halite lang) (:comment op) (or (:comment-j op) (:comment op)))
                  c-2 (if (= :halite lang) (:comment-2 op) (or (:comment-2-j op) (:comment-2 op)))
                  c-3 (if (= :halite lang) (:comment-3 op) (or (:comment-3-j op) (:comment-3 op)))]
              (when (or c-1 c-2 c-3) [(string/join " " [c-1 c-2 c-3]) "\n\n"]))
            (when-let [es (:examples op)]
              ["<table>"
               (for [row (text-tile-rows (map (partial example-text lang) es))]
                 ["<tr>"
                  (for [tile (:tiles row)]
                    ["<td colspan=\"" (:cols tile) "\">\n\n"
                     "```" ({:halite "clojure", :jadeite "java"} lang) "\n"
                     (:text tile)
                     "\n```\n\n</td>"])
                  "</tr>"])
               "</table>\n\n"])
            (when-let [t (:throws op)]
              ["#### Possible errors:\n\n"
               (for [msg (sort t)]
                 (str "* " "[`" msg "`]("
                      (if (= :halite lang)
                        "halite-err-id-reference.md"
                        "jadeite-err-id-reference.md")
                      "#" (safe-op-anchor msg) ")" "\n"))
               "\n"])
            (when-let [tags (let [t (if (= :halite lang)
                                      (:tags op)
                                      (or (:tags-j op)
                                          (:tags op)))]
                              (when (seq t)
                                t))]
              ["#### Tags:" "\n\n"
               (string/join ", "
                            (for [a (sort tags)]
                              (let [a (name a)]
                                (str " [" (:label (tag-def-map (keyword a))) "]("
                                     (tag-md-filename lang a)
                                     ")"))))
               "\n\n"])
            "---\n"]
           flatten (apply str)))))

(defn produce-basic-core-md [lang]
  (str (->> basic-bnf
            (partition 2)
            (map (partial apply basic-md lang))
            (apply str))
       "### Type Graph"
       "![" "type graph" "](./types.dot.png)\n\n"))

(defn produce-basic-md []
  (->> (produce-basic-core-md :halite)
       (str generated-msg "# Halite basic syntax reference\n\n")
       (spit "doc/halite-basic-syntax-reference.md"))
  (->> (produce-basic-core-md :jadeite)
       (str generated-msg "# Jadeite basic syntax reference\n\n")
       (spit "doc/jadeite-basic-syntax-reference.md")))

(defn err-md [lang err-id err]
  (->> ["### "
        "<a name=\"" (safe-op-anchor err-id) "\"></a>"
        err-id "\n\n" (:doc err) "\n\n"
        "#### Error message template:" "\n\n"
        "> " (:message err)
        "\n\n"
        (when-let [thrown-bys (:thrown-by-basic err)]
          ["#### Produced by elements:\n\n"
           (for [a (sort thrown-bys)]
             (str "* " "[`" a "`](" (if (= :halite lang)
                                      "halite-basic-syntax-reference.md"
                                      "jadeite-basic-syntax-reference.md")
                  "#" (safe-op-anchor a) ")" "\n"))
           "\n"])
        (when-let [thrown-bys (if (= :halite lang)
                                (:thrown-by err)
                                (:thrown-by-j err))]
          ["#### Produced by operators:\n\n"
           (for [a (sort thrown-bys)]
             (str "* " "[`" a "`](" (if (= :halite lang)
                                      "halite-full-reference.md"
                                      "jadeite-full-reference.md")
                  "#" (safe-op-anchor a) ")" "\n"))
           "\n"])
        (when-let [alsos (:see-also err)]
          ["See also:"
           (for [a (sort alsos)]
             [" [`" a "`](#" (safe-op-anchor a) ")"])
           "\n\n"])
        "---\n"]
       flatten (apply str)))

(defn produce-err-md []
  (->> err-maps
       sort
       (map (partial apply err-md :halite))
       (apply str generated-msg "# Halite err-id reference\n\n")
       (spit "doc/halite-err-id-reference.md"))
  (->> err-maps
       sort
       (map (partial apply err-md :jadeite))
       (apply str generated-msg "# Jadeite err-id reference\n\n")
       (spit "doc/jadeite-err-id-reference.md")))

(defn tag-md [lang tag-name tag]
  (->> [(:doc tag) "\n\n"
        (when-let [basic-ref (if (= :halite lang)
                               (:basic-ref tag)
                               (or (:basic-ref-j tag)
                                   (:basic-ref tag)))]
          ["For basic syntax of this data type see: [`" basic-ref "`]" "("
           (if (= :halite lang)
             "halite-basic-syntax-reference.md"
             "jadeite-basic-syntax-reference.md")
           "#" basic-ref
           ")" "\n\n"])
        ["![" (pr-str tag-name) "](./halite-bnf-diagrams/"
         (url-encode tag-name) (when (= :jadeite lang) "-j") ".svg)\n\n"]
        [(when-let [op-names ((if (= :halite lang) tag-map tag-map-j) (keyword tag-name))]
           (->> op-names
                (map (fn [op-name]
                       (let [op ((if (= :halite lang) op-maps op-maps-j) op-name)]
                         {:op-name op-name
                          :md (str "#### [`" op-name "`](" (if (= :halite lang)
                                                             "halite-full-reference.md"
                                                             "jadeite-full-reference.md")
                                   "#" (safe-op-anchor op-name) ")" "\n\n"
                                   (if (= :halite lang) (:doc op) (or (:doc-j op) (:doc op)))
                                   "\n\n")})))
                (sort-by :op-name)
                (map :md)))]
        "---\n"]
       flatten (apply str)))

(defn produce-tag-md [lang [tag-name tag]]
  (let [tag-name (name tag-name)]
    (->> (tag-md lang tag-name tag)
         (str generated-msg "# " (if (= :halite lang) "Halite" "Jadeite")
              " reference: "
              (:label tag)
              "\n\n")
         (spit (str "doc/" (tag-md-filename lang tag-name))))))

;;

(defn query-ops
  [tag]
  (apply sorted-map (mapcat identity (filter (fn [[op m]]
                                               (get (:tags m) tag))
                                             op-maps))))

(defn produce-bnf-diagram-for-tag [tag]
  (produce-bnf-diagrams
   (query-ops tag)
   (translate-op-maps-to-jadeite (query-ops tag))
   (str (name tag) ".svg")
   (str (name tag) "-j" ".svg")))

(comment
  (do
    (produce-basic-bnf-diagrams "basic-all.svg" "basic-all-j.svg" basic-bnf)

    (produce-bnf-diagrams op-maps op-maps-j "halite.svg" "jadeite.svg")

    (->> (keys tag-def-map)
         (map produce-bnf-diagram-for-tag)
         dorun)
    (->> tag-def-map
         (map (partial produce-tag-md :halite))
         dorun)
    (->> tag-def-map
         (map (partial produce-tag-md :jadeite))
         dorun)

    (produce-basic-md)
    (produce-full-md)
    (produce-err-md)))
