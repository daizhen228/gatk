import h5py
import numpy as np
from typing import Dict

from ml4h.TensorMap import TensorMap, Interpretation


def reference_tensor_from_hd5(tm: TensorMap, hd5: h5py.File, dependents: Dict = {}) -> np.ndarray:
    return np.array(hd5['reference'])


reference = TensorMap('reference', shape=(128,), tensor_from_file=reference_tensor_from_hd5)


def read_tensor_from_hd5(tm: TensorMap, hd5: h5py.File, dependents: Dict = {}) -> np.ndarray:
    return np.array(hd5['read_tensor'])


read_tensor = TensorMap('read_tensor', shape=(128, 128, 15), tensor_from_file=read_tensor_from_hd5)


def variant_label_from_hd5(tm: TensorMap, hd5: h5py.File, dependents: Dict = {}) -> np.ndarray:
    one_hot = np.zeros(tm.shape, dtype=np.float32)
    variant_label = str(hd5['variant_label'][()])
    for channel in tm.channel_map:
        if channel == variant_label:
            one_hot[tm.channel_map[channel]] = 1.0
    if one_hot.sum() != 1:
        raise ValueError(f'TensorMap {tm.name} missing or invalid label')
    return one_hot

variant_label_channel_map = {'NOT_SNP': 0, 'NOT_INDEL': 1, 'SNP': 2, 'INDEL': 3}
variant_label = TensorMap(
    'variant_label', Interpretation.CATEGORICAL,
    shape=(len(variant_label_channel_map),),
    tensor_from_file=variant_label_from_hd5,
    channel_map=variant_label_channel_map
)