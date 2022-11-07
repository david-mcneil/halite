;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite
  "Expression language for resource spec constraints and refinements that is almost, but
  not quite, a drop-in replacement for salt."
  (:require [clojure.set :as set]
            [com.viasat.halite.analysis :as analysis]
            [com.viasat.halite.base :as base]
            [com.viasat.halite.envs :as envs]
            [com.viasat.halite.eval :as eval]
            [com.viasat.halite.h-err :as h-err]
            [com.viasat.halite.lib.format-errors :refer [throw-err with-exception-data]]
            [com.viasat.halite.lint :as lint]
            [com.viasat.halite.type-check :as type-check]
            [com.viasat.halite.type-of :as type-of]
            [com.viasat.halite.types :as types]
            [com.viasat.halite.syntax-check :as syntax-check]
            [potemkin]
            [schema.core :as s]))

(set! *warn-on-reflection* true)

(s/defn eval-predicate :- Boolean
  [ctx :- eval/EvalContext
   tenv :- (s/protocol envs/TypeEnv)
   bool-expr
   spec-id :- types/NamespacedKeyword
   constraint-name :- (s/maybe base/ConstraintName)]
  (let [ctx (update ctx :senv envs/to-halite-spec-env)]
    (with-exception-data {:form bool-expr
                          :spec-id spec-id
                          :constraint-name (name constraint-name)}
      (type-check/type-check-constraint-expr (:senv ctx) tenv bool-expr))
    (eval/eval-predicate ctx tenv bool-expr spec-id constraint-name)))

(s/defn eval-refinement :- (s/maybe s/Any)
  "Returns an instance of type spec-id, projected from the instance vars in ctx,
  or nil if the guards prevent this projection."
  [ctx :- eval/EvalContext
   tenv :- (s/protocol envs/TypeEnv)
   spec-id :- types/NamespacedKeyword
   expr
   refinement-name :- (s/maybe String)]
  (let [ctx (update ctx :senv envs/to-halite-spec-env)]
    (if (contains? eval/*refinements* spec-id)
      (eval/*refinements* spec-id) ;; cache hit
      (do
        (with-exception-data {:form expr
                              :spec-id spec-id
                              :refinement-name refinement-name}
          (type-check/type-check-refinement-expr (:senv ctx) tenv spec-id expr))
        (eval/eval-refinement ctx tenv spec-id expr refinement-name)))))

(s/defn ^:private load-env
  "Evaluate the contents of the env to get instances loaded with refinements."
  [senv :- (s/protocol envs/SpecEnv)
   env :- (s/protocol envs/Env)]
  (let [senv (envs/to-halite-spec-env senv)
        empty-env (envs/env {})]
    ;; All runtime values are homoiconic. We eval them in an empty environment
    ;; to initialize refinements for all instances.
    (reduce
     (fn [env [k v]]
       (envs/bind env k (eval/eval-expr* {:env empty-env :senv senv} v)))
     empty-env
     (envs/bindings env))))

(s/defn ^:private type-check-env
  "Type check the contents of the type environment."
  [senv :- (s/protocol envs/SpecEnv)
   tenv :- (s/protocol envs/TypeEnv)
   env :- (s/protocol envs/Env)]
  (let [senv (envs/to-halite-spec-env senv)
        declared-symbols (set (keys (envs/scope tenv)))
        bound-symbols (set (keys (envs/bindings env)))
        unbound-symbols (set/difference declared-symbols bound-symbols)
        empty-env (envs/env {})]
    (when (seq unbound-symbols)
      (throw-err (h-err/symbols-not-bound {:unbound-symbols unbound-symbols, :tenv tenv, :env env})))
    (doseq [sym declared-symbols]
      (let [declared-type (get (envs/scope tenv) sym)
            ;; it is not necessary to setup the eval bindings for the following because the
            ;; instances have already been processed by load-env at this point
            value (eval/eval-expr* {:env empty-env :senv senv} (get (envs/bindings env) sym))
            actual-type (type-of/type-of senv tenv value)]
        (when-not (types/subtype? actual-type declared-type)
          (throw-err (h-err/value-of-wrong-type {:variable sym :value value :expected declared-type :actual actual-type})))))))

(defmacro optionally-with-eval-bindings [flag form]
  `(if ~flag
     (binding [eval/*eval-predicate-fn* eval-predicate
               eval/*eval-refinement-fn* eval-refinement]
       ~form)
     ~form))

(def default-eval-expr-options {:type-check-expr? true
                                :type-check-env? true
                                :type-check-spec-refinements-and-constraints? true
                                :check-for-spec-cycles? true})

(s/defn eval-expr :- s/Any
  "Evaluate a halite expression against the given type environment, and return the result. Optionally check the
  bindings in the environment are checked against the type environment before evaluation. Optionally
  type check the expression before evaluating it. Optionally type check any refinements or
  constraints involved with instance literals in the expr and env. By default all checks are
  performed."
  ([senv :- (s/protocol envs/SpecEnv)
    tenv :- (s/protocol envs/TypeEnv)
    env :- (s/protocol envs/Env)
    expr]
   (eval-expr senv tenv env expr default-eval-expr-options))

  ([senv :- (s/protocol envs/SpecEnv)
    tenv :- (s/protocol envs/TypeEnv)
    env :- (s/protocol envs/Env)
    expr
    options :- {(s/optional-key :type-check-expr?) Boolean
                (s/optional-key :type-check-env?) Boolean
                (s/optional-key :type-check-spec-refinements-and-constraints?) Boolean
                (s/optional-key :check-for-spec-cycles?) Boolean
                (s/optional-key :limits) base/Limits}]
   (let [senv (envs/to-halite-spec-env senv)
         {:keys [type-check-expr? type-check-env? type-check-spec-refinements-and-constraints? check-for-spec-cycles?
                 limits]} options]
     (binding [base/*limits* (or limits base/*limits*)]
       (when check-for-spec-cycles?
         (when (not (map? senv))
           (throw-err (h-err/spec-map-needed {})))
         (when-let [cycle (analysis/find-cycle-in-dependencies senv)]
           (throw-err (h-err/spec-cycle {:cycle cycle}))))
       (when type-check-expr?
         ;; it is not necessary to setup the eval bindings here because type-check does not invoke the
         ;; evaluator
         (type-check/type-check senv tenv expr))
       (let [loaded-env (optionally-with-eval-bindings
                         type-check-spec-refinements-and-constraints?
                         (load-env senv env))]
         (when type-check-env?
           ;; it is not necessary to setup the eval bindings here because env values were checked by load-env
           (type-check-env senv tenv loaded-env))
         (optionally-with-eval-bindings
          type-check-spec-refinements-and-constraints?
          (eval/eval-expr* {:env loaded-env :senv senv} expr)))))))

(defn syntax-check
  ([expr]
   (syntax-check expr {}))
  ([expr options]
   (let [{:keys [limits]} options]
     (binding [base/*limits* (or limits base/*limits*)]
       (syntax-check/syntax-check expr)))))

(defn type-check-and-lint
  ([senv tenv expr]
   (type-check-and-lint senv tenv expr))
  ([senv tenv expr options]
   (let [senv (envs/to-halite-spec-env senv)
         {:keys [limits]} options]
     (binding [base/*limits* (or limits base/*limits*)]
       (lint/type-check-and-lint senv tenv expr)))))

(defn type-check
  [senv & args]
  (let [senv (envs/to-halite-spec-env senv)]
    (apply type-check/type-check senv args)))

(defn type-check-spec
  [senv spec-info]
  (let [senv (envs/to-halite-spec-env senv)
        spec-info (envs/to-halite-spec spec-info)]
    (type-check/type-check-spec senv spec-info)))

(defn type-check-refinement-expr
  [senv & args]
  (let [senv (envs/to-halite-spec-env senv)]
    (apply type-check/type-check-refinement-expr senv args)))

(s/defn lookup-spec :- (s/maybe envs/UserSpecInfo)
  "Look up the spec with the given id in the given type environment, returning variable type information.
  Returns nil when the spec is not found."
  [senv :- (s/protocol envs/SpecEnv)
   spec-id :- types/NamespacedKeyword]
  (envs/lookup-spec* senv spec-id))

;;

(potemkin/import-vars
 [syntax-check ;; this is a namespace name, not a function name
  check-n])

(potemkin/import-vars
 [base
  integer-or-long? fixed-decimal? check-count
  Limits])

(potemkin/import-vars
 [base
  h< h> h<= h>= h+ h-])

(potemkin/import-vars
 [envs
  primitive-types
  Refinement MandatoryVarType VarType SpecVars RefinesTo UserSpecInfo ConstraintMap
  halite-type-from-var-type
  SpecEnv spec-env
  TypeEnv type-env type-env-from-spec
  Env env env-from-inst
  SpecMap
  ;; more advanced
  maybe-type? no-maybe])

(potemkin/import-vars
 [types
  HaliteType decimal-type vector-type set-type namespaced-keyword? abstract-spec-type concrete-spec-type
  nothing-like? join])
