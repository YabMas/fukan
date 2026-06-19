(ns canvas.architecture.reading.descent
  "Self-spec: the GENERATIVE-DESCENT readings (`fukan.descent`) — the Source in-fold witnesses as
   queries over the model. `required-witnesses` carves the design space (the polarities a Source
   declares must exist); the gap readings report which are unrealized (`unwitnessed-polarities`) or
   not yet verifiably unified (`unconverged-polarities`); `declared-witnesses` / `converged-polarities`
   are their realized duals. Reads the kernel's shared `StructureDb`; realizes the `Lens` faculty. A
   leaf (no cross-module calls — datalog over the graph)."
  (:require [lib.code :refer [Operation Module]]
            [canvas.architecture.kernel.structure :as kernel]
            [canvas.subject :as subj]))

(Module descent
  "The Source in-fold witnesses — required vs realized vs converged polarities, read from the model."
  {:realizes subj/Lens}                          ; faculty role: reads the graph for descent gaps
  (Operation required-witnesses "The polarities a Source declares must exist — the carved design space."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:set :string]]})
  (Operation declared-witnesses "The polarities actually realized by a witnessing structure."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:set :string]]})
  (Operation unwitnessed-polarities "Required polarities with no realizing witness — the witness gap."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:set :string]]})
  (Operation converged-polarities "Polarities whose :into Model verifiably unifies them."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:set :string]]})
  (Operation unconverged-polarities "Required polarities not yet verifiably unified — the convergence gap."
    {:signature [:=> [:catn [:db kernel/StructureDb]] [:set :string]]}))
