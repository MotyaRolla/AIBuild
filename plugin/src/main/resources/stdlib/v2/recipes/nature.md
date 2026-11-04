# RECIPE: tree / giant tree / forest / garden / park / water / treehouse
- A tree that is the subject: build it by hand, branch by branch (```guide trees): a trunk that flares into roots on
  the ground, a few thick limbs that fork, crown clusters at the limb tips with air between them, and a size that
  reads next to a player (a giant tree is 25-50 tall). Nothing around the subject should compete with it.
- Background trees: tree() with species, h, crown; vary heights and species, group them in clumps of 3-5, never in a
  grid, and never so many that they hide the subject.
- Water: an irregular shoreline, shallow edges (1 deep) stepping down to 3-4, a beach, reeds or rocks in groups at
  the edge, lily pads in a few patches; water that is part of the request should read from every side.
- Ground: groundCover() with forest:true under trees, terrain() for gentle relief, ferns/grass/flowers in clusters,
  fallen logs (line() with axisOf), mushrooms.
- Garden: paths of mixed blocks, hedges (bush), flower beds bordered by trapdoors/slabs, benches, lamp posts,
  a fountain or gazebo as the focal point.
