package com.fyn.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.fyn.app.core.AliasesStore
import com.fyn.app.databinding.SheetAliasesBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AliasesSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAliasesBinding? = null
    private val vb get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetAliasesBinding.inflate(inflater, container, false)
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val store = AliasesStore(requireContext())
        val aliases = store.getAliases()

        vb.etFacebook.setText(aliases[AliasesStore.Keys.FACEBOOK] ?: "")
        vb.etInstagram.setText(aliases[AliasesStore.Keys.INSTAGRAM] ?: "")
        vb.etSnapchat.setText(aliases[AliasesStore.Keys.SNAPCHAT] ?: "")
        vb.etLinkedin.setText(aliases[AliasesStore.Keys.LINKEDIN] ?: "")
        vb.etX.setText(aliases[AliasesStore.Keys.X] ?: "")
        vb.etTiktok.setText(aliases[AliasesStore.Keys.TIKTOK] ?: "")

        vb.btnSave.setOnClickListener {
            store.setAlias(AliasesStore.Keys.FACEBOOK, vb.etFacebook.text?.toString())
            store.setAlias(AliasesStore.Keys.INSTAGRAM, vb.etInstagram.text?.toString())
            store.setAlias(AliasesStore.Keys.SNAPCHAT, vb.etSnapchat.text?.toString())
            store.setAlias(AliasesStore.Keys.LINKEDIN, vb.etLinkedin.text?.toString())
            store.setAlias(AliasesStore.Keys.X, vb.etX.text?.toString())
            store.setAlias(AliasesStore.Keys.TIKTOK, vb.etTiktok.text?.toString())

            Toast.makeText(requireContext(), "Aliases saved", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        vb.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
