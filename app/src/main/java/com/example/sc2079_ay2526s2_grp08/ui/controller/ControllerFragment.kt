package com.example.sc2079_ay2526s2_grp08.ui.controller

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.sc2079_ay2526s2_grp08.MainActivity
import com.example.sc2079_ay2526s2_grp08.R
import com.example.sc2079_ay2526s2_grp08.domain.ArenaState
import com.example.sc2079_ay2526s2_grp08.domain.Facing
import com.example.sc2079_ay2526s2_grp08.domain.RobotDirection
import com.example.sc2079_ay2526s2_grp08.ui.arena.ArenaView
import kotlinx.coroutines.launch

class ControllerFragment : Fragment(R.layout.fragment_controller) {

    private val viewModel by lazy { (requireActivity() as MainActivity).viewModel }

    private var selectedId = 1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val arenaView = view.findViewById<ArenaView>(R.id.arenaView) ?: return

        val tvSelected = view.findViewById<TextView>(R.id.tvSelectedSmall)
        val tvConn = view.findViewById<TextView>(R.id.tvConnStatus)
        val tvNotif = view.findViewById<TextView>(R.id.tvNotifications)
        val tvRobotLoc = view.findViewById<TextView>(R.id.tvRobotLoc)

        fun setSelected(id: Int) {
            selectedId = id
            tvSelected?.text = "Selected: Obs $id"
        }

        view.findViewById<Button>(R.id.btnObs1)?.setOnClickListener { setSelected(1) }
        view.findViewById<Button>(R.id.btnObs2)?.setOnClickListener { setSelected(2) }
        view.findViewById<Button>(R.id.btnObs3)?.setOnClickListener { setSelected(3) }
        view.findViewById<Button>(R.id.btnObs4)?.setOnClickListener { setSelected(4) }
        view.findViewById<Button>(R.id.btnObs5)?.setOnClickListener { setSelected(5) }
        view.findViewById<Button>(R.id.btnObs6)?.setOnClickListener { setSelected(6) }
        view.findViewById<Button>(R.id.btnObs7)?.setOnClickListener { setSelected(7) }
        view.findViewById<Button>(R.id.btnObs8)?.setOnClickListener { setSelected(8) }

        setSelected(1)

        val s0 = viewModel.state.value
        if (s0.arena == null) {
            viewModel.initializeArena(ArenaState.DEFAULT_WIDTH, ArenaState.DEFAULT_HEIGHT)
        }
        if (s0.robot == null) {
            viewModel.setLocalRobotPosition(1, 1, RobotDirection.NORTH)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s ->
                    s.robot?.let { arenaView.setRobotPosition(it.x, it.y) }
                    arenaView.setObstacleBlocks(s.obstacleBlocks)

                    tvConn?.text = s.conn.name
                    tvNotif?.text = s.statusText ?: "-"

                    val r = s.robot
                    if (r != null) {
                        val face = when (r.robotDirection) {
                            RobotDirection.NORTH -> "N"
                            RobotDirection.EAST -> "E"
                            RobotDirection.SOUTH -> "S"
                            RobotDirection.WEST -> "W"
                        }
                        tvRobotLoc?.text = "X: ${r.x}, Y: ${r.y} (Facing: $face)"
                    } else {
                        tvRobotLoc?.text = "X: -, Y: - (Facing: -)"
                    }
                }
            }
        }

        arenaView.setListener(object : ArenaView.Listener {
            override fun onCellTap(x: Int, y: Int) {
                viewModel.placeOrMoveObstacle(selectedId, x, y)
            }

            override fun onObstacleTap(obstacleId: Int) {
                showFaceDialog(obstacleId)
            }

            override fun onObstacleDrag(obstacleId: Int, x: Int, y: Int) {
                viewModel.previewMoveObstacle(obstacleId, x, y)
            }

            override fun onObstacleDrop(obstacleId: Int, x: Int, y: Int) {
                viewModel.placeOrMoveObstacle(obstacleId, x, y)
            }

            override fun onObstacleRemove(obstacleId: Int) {
                viewModel.removeObstacle(obstacleId)
            }
        })

        view.findViewById<Button>(R.id.btnForward)?.setOnClickListener { viewModel.sendMoveForward() }
        view.findViewById<Button>(R.id.btnBack)?.setOnClickListener { viewModel.sendMoveBackward() }
        view.findViewById<Button>(R.id.btnLeft)?.setOnClickListener { viewModel.sendTurnLeft() }
        view.findViewById<Button>(R.id.btnRight)?.setOnClickListener { viewModel.sendTurnRight() }
        view.findViewById<Button>(R.id.btnStop)?.setOnClickListener { viewModel.sendRaw("STOP") }
    }

    private fun showFaceDialog(obstacleId: Int) {
        val options = arrayOf("N", "E", "S", "W")

        AlertDialog.Builder(requireContext())
            .setTitle("Obstacle $obstacleId facing")
            .setItems(options) { _, which ->
                val face = when (options[which]) {
                    "N" -> Facing.N
                    "E" -> Facing.E
                    "S" -> Facing.S
                    "W" -> Facing.W
                    else -> Facing.N
                }
                viewModel.setObstacleFacing(obstacleId, face)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
