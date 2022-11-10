;; Copyright (c) 2022 Viasat, Inc.
;; Licensed under the MIT license

(ns com.viasat.halite.propagate
  "Constraint propagation for halite."
  (:require [com.viasat.halite.envs :as envs]
            [com.viasat.halite.propagate.prop-abstract :as prop-abstract]
            [com.viasat.halite.propagate.prop-top-concrete :as prop-top-concrete]
            [com.viasat.halite.transpile.ssa :as ssa]
            [schema.core :as s]))

(set! *warn-on-reflection* true)

(def Bound prop-abstract/Bound)

(def SpecBound prop-abstract/SpecBound)

(def Opts prop-abstract/Opts)

(def default-options prop-abstract/default-options)

(s/defn propagate :- SpecBound
  ([senv :- (s/protocol envs/SpecEnv), initial-bound :- SpecBound]
   (propagate senv default-options initial-bound))
  ([senv :- (s/protocol envs/SpecEnv), opts :- prop-abstract/Opts, initial-bound :- SpecBound]
   (let [sctx (if (map? senv)
                (ssa/spec-map-to-ssa senv)
                (ssa/build-spec-ctx senv (:$type initial-bound)))]
     (prop-top-concrete/propagate sctx opts initial-bound))))

