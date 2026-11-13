package com.hazukieq.hkrime.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.hazukieq.hkrime.databinding.FragmentSetupBinding
import com.hazukieq.hkrime.setup.SetupPage.Companion.isLastPage

class SetupFragment : Fragment() {
    private val viewModel: SetupViewModel by activityViewModels()

    // ViewBinding 只能在 onCreateView() ~ onDestroyView() 之間使用：
    // Fragment 的生命週期比它的 View 長，若把 binding 一直留在字段裡，
    // 被 ViewPager2 回收的頁面會連帶把整棵 View 樹留在內存裡（官方文檔明確要求
    // 在 onDestroyView() 清空 binding 引用）。
    private var _binding: FragmentSetupBinding? = null
    private val binding get() = requireNotNull(_binding) { "binding accessed outside view lifecycle" }

    private val page: SetupPage by lazy { requireArguments().get("page") as SetupPage }

    private var isDone: Boolean = false
        set(new) {
            if (new && page.isLastPage())
                viewModel.isAllDone.value = true
            // sync() 可能在 Fragment 已無 View 時被呼叫（SetupActivity 的 window focus 回調），
            // 此時只記錄狀態，不碰 UI。
            _binding?.let { b ->
                with(b) {
                    stepText.text = page.getStepText(requireContext())
                    hintText.text = page.getHintText(requireContext())
                    actionButton.visibility = if (new) View.GONE else View.VISIBLE
                    actionButton.text = page.getButtonText(requireContext())
                    actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
                    doneText.visibility = if (new) View.VISIBLE else View.GONE
                }
            }
            field = new
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater)
        sync()
        return binding.root
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // Called on window focus changed
    fun sync() {
        isDone = page.isDone()
    }

    override fun onResume() {
        super.onResume()
        sync()
    }
}
